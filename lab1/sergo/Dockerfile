FROM golang:1.27.1
WORKDIR /app
COPY ./service/ .
RUN go build -o api ./main.go
CMD ["/app/api"]