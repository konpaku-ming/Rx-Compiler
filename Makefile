# Makefile for Rx-Compiler
# Requires: JDK 21+

# Paths
# Allow overriding JAVA via environment variable, default to system java
JAVA ?= java
LIB_DIR := lib
BUILTIN_FILE := builtin.c

# Java runtime configuration
JAVA_CP := $(LIB_DIR)/*
MAIN_CLASS := MainKt

# Build target: verify JAR files exist
.PHONY: build
build:
	@if [ ! -f $(LIB_DIR)/Rx-Compiler-1.0-SNAPSHOT.jar ]; then \
		echo "Error: JAR files not found in $(LIB_DIR)/"; \
		echo "Please ensure the lib/ directory contains the pre-built JAR files."; \
		exit 1; \
	fi
	@echo "Build complete (using pre-built JAR files)"

# Run target: run compiler reading from STDIN, output IR to STDOUT, builtin.c to STDERR
.PHONY: run
run:
	@$(JAVA) -cp "$(JAVA_CP)" $(MAIN_CLASS) -; test -f $(BUILTIN_FILE) && cat $(BUILTIN_FILE) >&2

# Clean build artifacts (no-op for pre-built JARs)
.PHONY: clean
clean:
	@echo "Clean complete (no build artifacts to remove)"

# Help target
.PHONY: help
help:
	@echo "Rx-Compiler Makefile"
	@echo ""
	@echo "Targets:"
	@echo "  build  - No-op (using pre-built JAR files)"
	@echo "  run    - Run the compiler (reads from STDIN, outputs IR to STDOUT, builtin.c to STDERR)"
	@echo "  clean  - No-op (no build artifacts to clean)"
	@echo "  help   - Show this help message"
	@echo ""
	@echo "Environment Variables:"
	@echo "  JAVA   - Java executable path (default: java). Use for JDK 21+ if not in PATH"
	@echo "           Example: JAVA=/path/to/jdk21/bin/java make run"
