package main

import (
	"context"
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/moby/moby/api/types/container"
	"github.com/moby/moby/api/types/network"
	"github.com/moby/moby/client"
)

// ProctorInstance 代表一个运行中的 proctor 容器。
type ProctorInstance struct {
	ExamID      string
	ContainerID string
	HostPort    string
	CreatedAt   time.Time
}

// ProctorRegistry 管理所有运行中的 proctor 容器，支持并发安全访问。
type ProctorRegistry struct {
	mu         sync.RWMutex
	instances  map[string]*ProctorInstance // key: examID
	dockerCli  *client.Client
	image      string
	redisAddr  string
	loggerAddr string
	javaAddr   string
	network    string
}

// NewProctorRegistry 创建注册表并校验 Docker 连接。
func NewProctorRegistry(image, redisAddr, loggerAddr, javaAddr, networkName string) (*ProctorRegistry, error) {
	cli, err := client.NewClientWithOpts(client.FromEnv, client.WithAPIVersionNegotiation())
	if err != nil {
		return nil, fmt.Errorf("failed to create docker client: %w", err)
	}
	return &ProctorRegistry{
		instances:  make(map[string]*ProctorInstance),
		dockerCli:  cli,
		image:      image,
		redisAddr:  redisAddr,
		loggerAddr: loggerAddr,
		javaAddr:   javaAddr,
		network:    networkName,
	}, nil
}

// GetOrCreate 获取已有容器或创建新容器，同一 examID 只创建一个。
func (r *ProctorRegistry) GetOrCreate(examID string) (*ProctorInstance, error) {
	r.mu.RLock()
	inst, ok := r.instances[examID]
	r.mu.RUnlock()
	if ok {
		// 检查容器是否还活着
		if r.containerExists(inst.ContainerID) {
			return inst, nil
		}
		// 容器已死，清理并重建
		r.removeInstance(examID)
	}
	return r.create(examID)
}

// Remove 停止并删除指定考试的 proctor 容器。
func (r *ProctorRegistry) Remove(examID string) error {
	r.mu.RLock()
	inst, ok := r.instances[examID]
	r.mu.RUnlock()
	if !ok {
		return fmt.Errorf("proctor for exam %s not found", examID)
	}

	ctx := context.Background()
	_, err := r.dockerCli.ContainerRemove(ctx, inst.ContainerID, client.ContainerRemoveOptions{Force: true})
	if err != nil {
		return fmt.Errorf("failed to remove container: %w", err)
	}
	r.removeInstance(examID)
	return nil
}

// CleanupExpired 清理超过 ttl 的容器。由后台 goroutine 定时调用。
func (r *ProctorRegistry) CleanupExpired(ttl time.Duration) []string {
	r.mu.Lock()
	defer r.mu.Unlock()

	var cleaned []string
	ctx := context.Background()
	cutoff := time.Now().Add(-ttl)

	for examID, inst := range r.instances {
		if inst.CreatedAt.Before(cutoff) {
			_, _ = r.dockerCli.ContainerRemove(ctx, inst.ContainerID, client.ContainerRemoveOptions{Force: true})
			delete(r.instances, examID)
			cleaned = append(cleaned, examID)
		}
	}
	return cleaned
}

// Close 关闭 Docker 客户端连接。
func (r *ProctorRegistry) Close() error {
	return r.dockerCli.Close()
}

func (r *ProctorRegistry) create(examID string) (*ProctorInstance, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	// 双重检查
	if inst, ok := r.instances[examID]; ok {
		if r.containerExists(inst.ContainerID) {
			return inst, nil
		}
		delete(r.instances, examID)
	}

	ctx := context.Background()
	containerName := fmt.Sprintf("proctor-exam-%s", examID)

	// 清理同名孤儿容器（Controller 重启后内存注册表丢失，但 Docker 容器可能还在）
	if _, inspectErr := r.dockerCli.ContainerInspect(ctx, containerName, client.ContainerInspectOptions{}); inspectErr == nil {
		log.Printf("removing orphaned container %s", containerName)
		if _, err := r.dockerCli.ContainerRemove(ctx, containerName, client.ContainerRemoveOptions{Force: true}); err != nil {
			return nil, fmt.Errorf("failed to remove orphaned container %s: %w", containerName, err)
		}
	}

	proctorPort := network.MustParsePort("9090/tcp")
	hostConfig := &container.HostConfig{
		PublishAllPorts: true,
	}
	if r.network != "" {
		hostConfig.NetworkMode = container.NetworkMode(r.network)
	}

	resp, err := r.dockerCli.ContainerCreate(ctx, client.ContainerCreateOptions{
		Config: &container.Config{
			Image: r.image,
			Env: []string{
				fmt.Sprintf("EXAM_ID=%s", examID),
				fmt.Sprintf("REDIS_ADDR=%s", r.redisAddr),
				fmt.Sprintf("LOGGER_ADDR=%s", r.loggerAddr),
				fmt.Sprintf("JAVA_ADDR=%s", r.javaAddr),
			},
			ExposedPorts: network.PortSet{proctorPort: struct{}{}},
		},
		HostConfig: hostConfig,
		Name:       containerName,
	})
	if err != nil {
		return nil, fmt.Errorf("failed to create container: %w", err)
	}

	if _, err := r.dockerCli.ContainerStart(ctx, resp.ID, client.ContainerStartOptions{}); err != nil {
		return nil, fmt.Errorf("failed to start container: %w", err)
	}

	inspect, err := r.dockerCli.ContainerInspect(ctx, resp.ID, client.ContainerInspectOptions{})
	if err != nil {
		return nil, fmt.Errorf("failed to inspect container: %w", err)
	}

	bindings := inspect.Container.NetworkSettings.Ports[proctorPort]
	if len(bindings) == 0 || bindings[0].HostPort == "" {
		return nil, fmt.Errorf("failed to resolve host port for %s", proctorPort)
	}

	inst := &ProctorInstance{
		ExamID:      examID,
		ContainerID: resp.ID,
		HostPort:    bindings[0].HostPort,
		CreatedAt:   time.Now(),
	}
	r.instances[examID] = inst
	return inst, nil
}

func (r *ProctorRegistry) containerExists(containerID string) bool {
	ctx := context.Background()
	_, err := r.dockerCli.ContainerInspect(ctx, containerID, client.ContainerInspectOptions{})
	return err == nil
}

func (r *ProctorRegistry) removeInstance(examID string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.instances, examID)
}
