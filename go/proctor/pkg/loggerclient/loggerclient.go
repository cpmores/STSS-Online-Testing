package loggerclient

import (
	"context"
	"log"
	"time"

	pb "logger/pkg/loggerServer/v1"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

const (
	dialTimeout = 3 * time.Second
	logTimeout  = 2 * time.Second
)

type Client struct {
	service  string
	addr     string
	conn     *grpc.ClientConn
	api      pb.LoggerServiceClient
	disabled bool
}

type Fields struct {
	OperationID  int32
	TraceID      string
	UserID       string
	Method       string
	Path         string
	StatusCode   int32
	DurationMS   int64
	EntityType   string
	EntityID     string
	StringFields map[string]string
	IntFields    map[string]int64
	ErrorMessage string
	StackTrace   string
}

func New(addr, service string) (*Client, error) {
	if addr == "" {
		return &Client{disabled: true}, nil
	}

	ctx, cancel := context.WithTimeout(context.Background(), dialTimeout)
	defer cancel()

	conn, err := grpc.DialContext(ctx, addr, grpc.WithTransportCredentials(insecure.NewCredentials()), grpc.WithBlock())
	if err != nil {
		return &Client{disabled: true}, err
	}

	return &Client{
		service: service,
		addr:    addr,
		conn:    conn,
		api:     pb.NewLoggerServiceClient(conn),
	}, nil
}

func (c *Client) Close() error {
	if c == nil || c.conn == nil {
		return nil
	}
	return c.conn.Close()
}

func (c *Client) Log(ctx context.Context, level pb.LogLevel, message string, fields Fields) error {
	if c == nil || c.disabled || c.api == nil || message == "" {
		return nil
	}
	if ctx == nil {
		ctx = context.Background()
	}

	ctx, cancel := context.WithTimeout(ctx, logTimeout)
	defer cancel()

	req := &pb.LogRequest{
		Level:        level,
		Service:      c.service,
		OperationId:  fields.OperationID,
		TraceId:      fields.TraceID,
		UserId:       fields.UserID,
		Message:      message,
		Method:       fields.Method,
		Path:         fields.Path,
		StatusCode:   fields.StatusCode,
		DurationMS:   fields.DurationMS,
		EntityType:   fields.EntityType,
		EntityId:     fields.EntityID,
		StringFields: fields.StringFields,
		IntFields:    fields.IntFields,
		ErrorMessage: fields.ErrorMessage,
		StackTrace:   fields.StackTrace,
	}

	_, err := c.api.Log(ctx, req)
	if err != nil {
		log.Printf("logger send failed (%s): %v", c.addr, err)
	}
	return err
}
