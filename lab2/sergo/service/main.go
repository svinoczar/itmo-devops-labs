package main

import (
	"context"
	"fmt"
	"log/slog"
	"math/rand"
	"net/http"
	"os"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.17.0"
	"go.opentelemetry.io/otel/trace"
)

var (
	reqCounter = prometheus.NewCounterVec(
		prometheus.CounterOpts{Name: "http_requests_total", Help: "Total requests"},
		[]string{"path", "status"},
	)
	errCounter = prometheus.NewCounterVec(
		prometheus.CounterOpts{Name: "http_errors_total", Help: "Total errors"},
		[]string{"path"},
	)
	reqDuration = prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "http_request_duration_seconds",
			Help:    "Request duration",
			Buckets: prometheus.DefBuckets,
		},
		[]string{"path"},
	)
	tracer trace.Tracer
)

func init() {
	prometheus.MustRegister(reqCounter, errCounter, reqDuration)
}

// Кастомный обработчик для JSON логов с поддержкой trace_id
type otelLogHandler struct {
	slog.Handler
}

func (h *otelLogHandler) Handle(ctx context.Context, r slog.Record) error {
	spanContext := trace.SpanContextFromContext(ctx)
	if spanContext.IsValid() {
		r.AddAttrs(slog.String("trace_id", spanContext.TraceID().String()))
	}
	return h.Handler.Handle(ctx, r)
}

func initTracer() *sdktrace.TracerProvider {
	exporter, err := otlptracegrpc.New(context.Background())
	if err != nil {
		fmt.Printf("Failed to create exporter: %v\n", err)
	}
	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(resource.NewWithAttributes(
			semconv.SchemaURL,
			semconv.ServiceName("api-service"),
		)),
	)
	otel.SetTracerProvider(tp)
	return tp
}

func measureMidleware(path string, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rw := &responseWriter{w, http.StatusOK}

		next(rw, r)

		duration := time.Since(start).Seconds()
		reqCounter.WithLabelValues(path, fmt.Sprintf("%d", rw.statusCode)).Inc()
		reqDuration.WithLabelValues(path).Observe(duration)
	}
}

type responseWriter struct {
	http.ResponseWriter
	statusCode int
}

func (rw *responseWriter) WriteHeader(code int) {
	rw.statusCode = code
	rw.ResponseWriter.WriteHeader(code)
}

func main() {
	// Настройка JSON логгера
	logger := slog.New(&otelLogHandler{slog.NewJSONHandler(os.Stdout, nil)})
	slog.SetDefault(logger)

	tp := initTracer()
	defer tp.Shutdown(context.Background())
	tracer = otel.Tracer("api-tracer")

	mux := http.NewServeMux()

	mux.HandleFunc("/health", measureMidleware("/health", func(w http.ResponseWriter, r *http.Request) {
		slog.InfoContext(r.Context(), "Health check passed")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("ok"))
	}))

	mux.HandleFunc("/fail", measureMidleware("/fail", func(w http.ResponseWriter, r *http.Request) {
		span := trace.SpanFromContext(r.Context())
		span.SetStatus(codes.Error, "Simulated internal error")

		errCounter.WithLabelValues("/fail").Inc()
		slog.ErrorContext(r.Context(), "Something went terribly wrong")

		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte("error"))
	}))

	mux.HandleFunc("/slow", measureMidleware("/slow", func(w http.ResponseWriter, r *http.Request) {
		ctx, childSpan := tracer.Start(r.Context(), "slow-op")

		slog.InfoContext(ctx, "Starting slow operation")
		sleepTime := time.Duration(rand.Intn(2000)+1000) * time.Millisecond
		time.Sleep(sleepTime)

		childSpan.End()
		w.Write([]byte(fmt.Sprintf("slept for %v", sleepTime)))
	}))

	mux.HandleFunc("/load", measureMidleware("/load", func(w http.ResponseWriter, r *http.Request) {
		slog.InfoContext(r.Context(), "Generating load")
		for i := 0; i < 20; i++ {
			go http.Get("http://localhost:8080/health")
		}
		w.Write([]byte("load generated"))
	}))

	mux.Handle("/metrics", promhttp.Handler())

	// Оборачиваем роутер в OTel middleware
	handler := otelhttp.NewHandler(mux, "api-server")

	slog.Info("Server starting on :8080")
	http.ListenAndServe(":8080", handler)
}
