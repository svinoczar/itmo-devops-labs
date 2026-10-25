package main

import (
	"encoding/json"
	"log"
	"net/http"
	"os"
)

func main() {
	// Настраиваем логирование в stdout, чтобы можно было перенаправить в файл
	logger := log.New(os.Stdout, "", log.LstdFlags)

	// Эндпоинт 1: Теперь возвращает 400 Bad Request (отсутствует обязательный параметр)
	http.HandleFunc("/api/v1/transactions/status", func(w http.ResponseWriter, r *http.Request) {
		logger.Println("[ERROR] GET /api/v1/transactions/status: missing mandatory query parameter 'tx_id'")
		w.WriteHeader(http.StatusBadRequest)
		json.NewEncoder(w).Encode(map[string]string{"error": "missing tx_id"})
	})

	// Эндпоинт 2: Теперь возвращает 401 Unauthorized (эмуляция сбоя из-за добавленной проверки заголовка)
	http.HandleFunc("/internal/webhook/billing-sync", func(w http.ResponseWriter, r *http.Request) {
		logger.Println("[ERROR] POST /internal/webhook/billing-sync: unauthorized, X-Client-ID header missing")
		w.WriteHeader(http.StatusUnauthorized)
		json.NewEncoder(w).Encode(map[string]string{"error": "unauthorized"})
	})

	// Эндпоинт 3: Эмулирует сбой, возвращает 500 и пишет стек-трейс в лог
	http.HandleFunc("/api/v1/transactions/route", func(w http.ResponseWriter, r *http.Request) {
		logger.Println("[ERROR] E2E_Test_04 failed on /api/v1/transactions/route")
		logger.Println("[FATAL] panic: runtime error: invalid memory address or nil pointer dereference")
		logger.Println("goroutine 34 [running]:")
		logger.Println("main.ProcessPayment(...)")
		logger.Println("	/app/internal/services/payment.go:42 +0x1a4")

		http.Error(w, "Internal Server Error", http.StatusInternalServerError)
	})

	logger.Println("Starting mock transaction-service on :8080...")
	log.Fatal(http.ListenAndServe(":8080", nil))
}
