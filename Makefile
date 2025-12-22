# Makefile for Rx-Compiler
# Requires: JDK 21+, kotlinc
# Note: This Makefile is designed for Unix-like systems (Linux, macOS)

# Paths
# Allow overriding JAVA and KOTLINC via environment variables
JAVA ?= java
KOTLINC ?= kotlinc
SRC_DIR := src/main/kotlin
BUILD_DIR := build
CLASSES_DIR := $(BUILD_DIR)/classes
JAR_FILE := $(BUILD_DIR)/Rx-Compiler.jar
BUILTIN_FILE := builtin.c

# Kotlin standard library path
KOTLINC_PATH := $(shell readlink -f $$(which $(KOTLINC)) 2>/dev/null || echo "")
KOTLINC_LIB_DIR := $(shell if [ -n "$(KOTLINC_PATH)" ]; then dirname $(KOTLINC_PATH); fi)/../lib
KOTLIN_STDLIB := $(KOTLINC_LIB_DIR)/kotlin-stdlib.jar

# Main class
MAIN_CLASS := MainKt

# Find all Kotlin source files
KOTLIN_SOURCES := $(shell find $(SRC_DIR) -name "*.kt" 2>/dev/null || echo "")

# Build target: compile the compiler
.PHONY: build
build: $(JAR_FILE)

$(JAR_FILE): $(KOTLIN_SOURCES)
	@if [ -z "$(KOTLIN_SOURCES)" ]; then \
		echo "Error: No Kotlin source files found in $(SRC_DIR)"; \
		exit 1; \
	fi
	@if [ ! -f "$(KOTLIN_STDLIB)" ]; then \
		echo "Error: Kotlin standard library not found at $(KOTLIN_STDLIB)"; \
		echo "Please ensure kotlinc is installed and in PATH"; \
		exit 1; \
	fi
	@mkdir -p $(CLASSES_DIR)
	@echo "Compiling Kotlin sources..."
	@$(KOTLINC) -jvm-target 21 -d $(CLASSES_DIR) $(KOTLIN_SOURCES)
	@echo "Creating JAR file..."
	@jar cf $(CURDIR)/$(JAR_FILE) -C $(CLASSES_DIR) .
	@echo "Build complete: $(JAR_FILE)"

# Run target: run compiler reading from STDIN, output IR to STDOUT, builtin.c to STDERR
.PHONY: run
run: build
	@$(JAVA) -cp "$(CURDIR)/$(JAR_FILE):$(KOTLIN_STDLIB)" $(MAIN_CLASS) -; test -f $(BUILTIN_FILE) && cat $(BUILTIN_FILE) >&2

# Clean build artifacts
.PHONY: clean
clean:
	@rm -rf $(BUILD_DIR)

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
	@echo ""
	@echo "Environment Variables:"
	@echo "  JAVA    - Java executable path (default: java). Use for JDK 21+ if not in PATH"
	@echo "  KOTLINC - Kotlin compiler path (default: kotlinc)"
	@echo "           Example: JAVA=/path/to/jdk21/bin/java make run"
	@echo ""
	@echo "Requirements:"
	@echo "  - JDK 21 or higher"
	@echo "  - kotlinc (Kotlin compiler)"
	@echo "  - Unix-like system (Linux, macOS, WSL)"
