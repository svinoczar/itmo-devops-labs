package main

import (
	"fmt"
	"net/http"
	"strconv"
	"sync/atomic"
)

var (
	memoryHog [][]byte
	isBurning atomic.Bool
)

func healthHandler(w http.ResponseWriter, r *http.Request) {
	fmt.Fprint(w, "ok\n")
}

func eatHandler(w http.ResponseWriter, r *http.Request) {
	mbStr := r.URL.Query().Get("mb")
	mb, err := strconv.Atoi(mbStr)
	if err != nil || mb <= 0 || mb > 512 {
		http.Error(w, "Invalid or missing 'mb' parameter. Example: /eat?mb=50", http.StatusBadRequest)
		return
	}
	chunk := make([]byte, mb*1024*1024)
	memoryHog = append(memoryHog, chunk)

	fmt.Fprintf(w, "Allocated %d MB. Total chunks: %d\n", mb, len(memoryHog))
}

func burnHandler(w http.ResponseWriter, r *http.Request) {
	if isBurning.Swap(true) {
		go func() {
			for isBurning.Load() {
			}
		}()
		fmt.Fprint(w, "Started CPU burn in background goroutine\n")
	}
}

func stopBurnHandler(w http.ResponseWriter, r *http.Request) {
	isBurning.Store(false)
	fmt.Fprint(w, "CLosed CPU burn\n")
}

func main() {

	http.HandleFunc("/health", healthHandler)
	http.HandleFunc("/eat", eatHandler)
	http.HandleFunc("/burn", burnHandler)
	http.HandleFunc("/stopBurn", stopBurnHandler)

	fmt.Println("Server is running on http://0.0.0.0:8080")
	if err := http.ListenAndServe(":8080", nil); err != nil {
		fmt.Printf("Server failed: %v\n", err)
	}
}
