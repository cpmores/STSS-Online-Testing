FROM docker.m.daocloud.io/library/golang:1.26-alpine AS builder

WORKDIR /app

COPY go/logger/go.mod go/logger/go.sum ./
RUN go mod download

COPY go/logger/cmd cmd
COPY go/logger/configs configs
COPY go/logger/pkg pkg

RUN CGO_ENABLED=0 GOOS=linux go build -o /out/logger-server ./cmd/server

FROM docker.m.daocloud.io/library/alpine:3.22

RUN apk add --no-cache ca-certificates && mkdir -p /log/ot

WORKDIR /app

COPY --from=builder /out/logger-server /app/logger-server

EXPOSE 50051

ENTRYPOINT ["/app/logger-server"]
