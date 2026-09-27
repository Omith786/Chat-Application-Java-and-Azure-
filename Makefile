# Convenience targets. Everything goes through the Maven Wrapper, so only a JDK 21 is needed.
# Variables from .env (if present) are exported to the server.

-include .env
export

MVNW := ./mvnw
SERVER_JAR := chat-server/target/chat-server.jar
CLI_JAR := chat-cli/target/chat-cli-1.0.0-all.jar
CHAT_SERVER ?= http://localhost:8080

.PHONY: help build test run run-postgres run-azure cli docker clean

help:
	@echo "make build          compile and package (skips tests)"
	@echo "make test           run the full test suite (./mvnw verify)"
	@echo "make run            start the server on http://localhost:8080"
	@echo "make run-postgres   start the server against PostgreSQL (DATABASE_URL etc.)"
	@echo "make run-azure      start the server with the Azure Web PubSub backplane"
	@echo "make cli NAME=alice start the terminal client"
	@echo "make docker         build the container image"
	@echo "make clean          remove build output"

build:
	$(MVNW) -B -DskipTests package

test:
	$(MVNW) -B verify

$(SERVER_JAR) $(CLI_JAR):
	$(MVNW) -B -DskipTests package

run: $(SERVER_JAR)
	java -jar $(SERVER_JAR)

run-postgres: $(SERVER_JAR)
	SPRING_PROFILES_ACTIVE=postgres java -jar $(SERVER_JAR)

# Locally, keep the database under ./data instead of App Service's /home.
run-azure: $(SERVER_JAR)
	SPRING_PROFILES_ACTIVE=azure CHAT_DATA_DIR=$${CHAT_DATA_DIR:-./data} java -jar $(SERVER_JAR)

cli: $(CLI_JAR)
	java -jar $(CLI_JAR) --server $(CHAT_SERVER) $(if $(NAME),--user $(NAME),)

docker:
	docker build -t chat-server .

clean:
	$(MVNW) -B -q clean
