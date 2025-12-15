# Makefile for Rx-Compiler
# Requires: JDK 21+, Gradle

# Paths
JAVA := /usr/lib/jvm/temurin-21-jdk-amd64/bin/java
GRADLE := ./gradlew
BUILD_DIR := build/install/Rx-Compiler
LIB_DIR := $(BUILD_DIR)/lib
BUILTIN_FILE := builtin.c

# Java runtime configuration
JAVA_CP := $(LIB_DIR)/*
MAIN_CLASS := MainKt

# Build target: compile the compiler
.PHONY: build
build:
	@$(GRADLE) build installDist --quiet

# Run target: run compiler reading from STDIN, output IR to STDOUT, builtin.c to STDERR
.PHONY: run
run:
	@$(JAVA) -cp "$(JAVA_CP)" $(MAIN_CLASS) - && cat $(BUILTIN_FILE) >&2

# Clean build artifacts
.PHONY: clean
clean:
	@$(GRADLE) clean --quiet

# Help target
.PHONY: help
help:
	@echo "Rx-Compiler Makefile"
	@echo ""
	@echo "Targets:"
	@echo "  build  - Compile the compiler"
	@echo "  run    - Run the compiler (reads from STDIN, outputs IR to STDOUT, builtin.c to STDERR)"
	@echo "  clean  - Clean build artifacts"
	@echo "  help   - Show this help message"
