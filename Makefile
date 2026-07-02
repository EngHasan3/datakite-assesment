.DEFAULT_GOAL := help

.PHONY: help up build down restart logs ps clean test backend-dev frontend-dev

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*## "}; {printf "  \033[36m%-15s\033[0m %s\n", $$1, $$2}'

up: ## Build (if needed) and start the full stack: Postgres, backend, frontend
	docker compose up --build -d

build: ## Build the backend and frontend images without starting them
	docker compose build

down: ## Stop and remove the stack's containers (keeps the Postgres volume)
	docker compose down

restart: down up ## Restart the full stack

logs: ## Follow logs from every service (Ctrl+C to stop)
	docker compose logs -f

ps: ## Show the status of the stack's containers
	docker compose ps

clean: ## Stop the stack and delete the Postgres volume (destroys all data)
	docker compose down -v

test: ## Run the backend test suite (needs Docker for Testcontainers)
	cd backend && ./mvnw test

backend-dev: ## Run the backend locally against a Postgres container on :5432 (no Docker build)
	docker run -d --name ledger-postgres -e POSTGRES_DB=ledger -e POSTGRES_USER=ledger \
		-e POSTGRES_PASSWORD=ledger -p 5432:5432 postgres:16-alpine 2>/dev/null || true
	cd backend && ./mvnw spring-boot:run

frontend-dev: ## Run the frontend locally against a backend on localhost:8080
	cd frontend && npm install && npm run dev
