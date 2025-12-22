# Makefile for Rx-Compiler
# Requires: JDK 21+, kotlinc

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
KOTLINC_PATH := $(shell readlink -f $$(which $(KOTLINC)))
KOTLINC_LIB_DIR := $(shell dirname $(KOTLINC_PATH))/../lib
KOTLIN_STDLIB := $(KOTLINC_LIB_DIR)/kotlin-stdlib.jar

# Main class
MAIN_CLASS := MainKt

# Find all Kotlin source files
KOTLIN_SOURCES := $(shell find $(SRC_DIR) -name "*.kt")

# Build target: compile the compiler
.PHONY: build
build: $(JAR_FILE)

$(JAR_FILE): $(KOTLIN_SOURCES)
	@mkdir -p $(CLASSES_DIR)
	@echo "Compiling Kotlin sources..."
	@$(KOTLINC) -jvm-target 21 -d $(CLASSES_DIR) $(KOTLIN_SOURCES)
	@echo "Creating JAR file..."
	@cd $(CLASSES_DIR) && jar cf ../../$(JAR_FILE) .
	@echo "Build complete: $(JAR_FILE)"

# Run target: run compiler reading from STDIN, output IR to STDOUT, builtin.c to STDERR
.PHONY: run
run: build
	@$(JAVA) -cp "$(JAR_FILE):$(KOTLIN_STDLIB)" $(MAIN_CLASS) -; test -f $(BUILTIN_FILE) && cat $(BUILTIN_FILE) >&2

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
