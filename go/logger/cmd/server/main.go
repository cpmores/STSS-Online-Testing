package main

import (
	"context"
	"errors"
	"net"
	"os"

	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
	"google.golang.org/grpc"
	"google.golang.org/grpc/reflection"
	"gopkg.in/natefinch/lumberjack.v2"

	pb "logger/pkg/loggerServer/v1"
)

type loggerServer struct {
	pb.UnimplementedLoggerServiceServer
	logger *zap.Logger
}

func filter(req *pb.LogRequest) error {
	// TODO
	if req.Message == "" {
		return errors.New("message is required")
	}

	return nil
}

func (l *loggerServer) Log(ctx context.Context, req *pb.LogRequest) (*pb.LogResponse, error) {

	// cannot pass filter (bad field)
	if err := filter(req); err != nil {
		return &pb.LogResponse{
			Success:      false,
			ErrorCode:    400,
			ErrorMessage: err.Error(),
		}, nil
	}

	level := req.Level
	if level == pb.LogLevel_LOG_LEVEL_UNSPECIFIED {
		level = pb.LogLevel_LOG_LEVEL_INFO
		l.logger.Info("Level not specified, defaulting to INFO")
	}

	service := req.Service
	if service == "" {
		service = "unknown"
	}

	fields := []zap.Field{
		zap.String("service", service),
		zap.String("trace_id", req.TraceId),
		zap.String("user_id", req.UserId),
	}

	if req.Method != "" {
		fields = append(fields, zap.String("method", req.Method))
	}
	if req.Path != "" {
		fields = append(fields, zap.String("path", req.Path))
	}
	if req.StatusCode != 0 {
		fields = append(fields, zap.Int32("status_code", req.StatusCode))
	}
	if req.DurationMS != 0 {
		fields = append(fields, zap.Int64("durations_ms", req.DurationMS))
	}

	switch level {
	case pb.LogLevel_LOG_LEVEL_DEBUG:
		l.logger.Debug(req.Message, fields...)
	case pb.LogLevel_LOG_LEVEL_INFO:
		l.logger.Info(req.Message, fields...)
	case pb.LogLevel_LOG_LEVEL_WARN:
		l.logger.Warn(req.Message, fields...)
	case pb.LogLevel_LOG_LEVEL_ERROR:
		l.logger.Error(req.Message, fields...)
	}

	return &pb.LogResponse{
		Success: true,
	}, nil
}

func (l *loggerServer) BatchLog(stream pb.LoggerService_BatchLogServer) error {
	return nil
}

func ensureLogDir() error {
	return os.MkdirAll("../../log/ot/", 0755)
}

func initLogger() (*zap.Logger, error) {
	if err := ensureLogDir(); err != nil {
		return nil, err
	}

	writer := zapcore.AddSync(&lumberjack.Logger{
		Filename:   "../../log/ot/app.log",
		MaxSize:    100,
		MaxBackups: 10,
		MaxAge:     30,
		Compress:   true,
	})

	encoderConfig := zap.NewProductionEncoderConfig()
	encoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder
	encoderConfig.EncodeLevel = zapcore.CapitalLevelEncoder
	encoder := zapcore.NewJSONEncoder(encoderConfig)

	core := zapcore.NewCore(encoder, writer, zapcore.InfoLevel)
	logger := zap.New(core)
	return logger, nil
}

func main() {
	logger, err := initLogger()
	if err != nil {
		panic("Failed to initialize logger: " + err.Error())
	}
	defer logger.Sync()

	// server
	listen, err := net.Listen("tcp", ":50051")
	if err != nil {
		logger.Fatal("Failed to listen", zap.Error(err))
	}

	s := grpc.NewServer()
	pb.RegisterLoggerServiceServer(s, &loggerServer{
		logger: logger})

	reflection.Register(s)
	logger.Info("gRPC server starting", zap.String("address", ":50051"))
	if err := s.Serve(listen); err != nil {
		logger.Fatal("Failed to serve", zap.Error(err))
	}
}
