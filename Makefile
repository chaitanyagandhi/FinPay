# FinPay local development commands.
#
# Run `make` or `make help` for the list. Every Maven target runs against a JDK 21,
# located automatically by infrastructure/scripts/java-home.sh, so you do not have to
# set JAVA_HOME yourself.

SHELL := /bin/bash
.DEFAULT_GOAL := help

SCRIPTS := infrastructure/scripts
COMPOSE := docker compose
MVNW    := ./mvnw

# Which service database `make db` opens, e.g. `make db SERVICE=auth`.
SERVICE ?= wallet

# Restrict `make logs` to one container, e.g. `make logs CONTAINER=kafka`. Empty means all.
CONTAINER ?=

# Mirrors the docker-compose.yml default; override in .env or on the command line.
REDIS_PASSWORD ?= finpay

# Which configuration `make config` fetches, and the credentials it uses.
APP             ?= application
PROFILE         ?= docker
CONFIG_USER     ?= finpay
CONFIG_PASSWORD ?= finpay
CONFIG_PORT     ?= 8888

# Resolved once per invocation and exported to every recipe.
export JAVA_HOME := $(shell $(SCRIPTS)/java-home.sh 2>/dev/null)

.PHONY: help doctor env up images down stop restart reset ps logs health \
        build test verify format db redis topics config clean

##@ Getting started

help: ## Show this help
	@awk 'BEGIN {FS = ":.*##"; printf "\nFinPay development commands\n"} \
	  /^##@/ { printf "\n\033[1m%s\033[0m\n", substr($$0, 5); next } \
	  /^[a-zA-Z_-]+:.*?##/ { printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@printf '\nCurrent JAVA_HOME: %s\n\n' "$${JAVA_HOME:-<not found, run make doctor>}"

doctor: ## Check that this machine can build and run FinPay
	@$(SCRIPTS)/doctor.sh

env: ## Create .env from .env.example if it does not exist
	@if [ -f .env ]; then \
	  echo ".env already exists, leaving it alone"; \
	else \
	  cp .env.example .env && echo "created .env from .env.example"; \
	fi

##@ Infrastructure

up: ## Start the infrastructure and application services, waiting until healthy
	@$(COMPOSE) up -d --wait
	@$(MAKE) --no-print-directory ps

images: ## Rebuild service container images (needed after changing service source)
	@$(COMPOSE) build

down: ## Stop containers, keeping all data
	@$(COMPOSE) down

stop: ## Pause containers without removing them
	@$(COMPOSE) stop

restart: ## Restart the stack, keeping all data
	@$(COMPOSE) down
	@$(MAKE) --no-print-directory up

reset: ## Stop and DELETE all data, then start fresh (re-runs database init)
	@printf 'This deletes every local database, Redis key and Kafka topic. Continue? [y/N] '
	@read -r reply; [ "$$reply" = "y" ] || [ "$$reply" = "Y" ] || { echo "aborted"; exit 1; }
	@$(COMPOSE) down -v
	@$(MAKE) --no-print-directory up

ps: ## Show container state and health
	@$(COMPOSE) ps --format 'table {{.Name}}\t{{.Status}}\t{{.Ports}}'

logs: ## Follow logs for all containers, or one (make logs CONTAINER=kafka)
	@$(COMPOSE) logs -f --tail=100 $(CONTAINER)

health: ## Print the health state of every container
	@$(COMPOSE) ps --format '{{.Name}}: {{.Status}}'

##@ Build

build: ## Compile and package every module
	@$(MVNW) clean package

test: ## Run unit tests only
	@$(MVNW) test

verify: ## Full build: format check, unit tests, integration tests, coverage
	@$(MVNW) clean verify

format: ## Apply code formatting (run before committing)
	@$(MVNW) spotless:apply

clean: ## Remove Maven build output
	@$(MVNW) clean

##@ Shells

db: ## psql into a service database as its own role (make db SERVICE=auth)
	@$(SCRIPTS)/db-shell.sh $(SERVICE)

redis: ## Open an authenticated redis-cli session
	@$(COMPOSE) exec redis redis-cli -a '$(REDIS_PASSWORD)' --no-auth-warning

topics: ## List Kafka topics
	@$(COMPOSE) exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

config: ## Show configuration the config server serves (make config PROFILE=docker APP=application)
	@curl -sS -u '$(CONFIG_USER):$(CONFIG_PASSWORD)' \
	  http://localhost:$(CONFIG_PORT)/$(APP)/$(PROFILE) | python3 -m json.tool
