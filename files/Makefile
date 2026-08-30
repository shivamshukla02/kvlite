JAVAC := javac
JAVA  := java
BUILD_DIR := build
SRC_DIR := src
TEST_DIR := tests

.PHONY: build test clean run

build:
	mkdir -p $(BUILD_DIR)
	$(JAVAC) -d $(BUILD_DIR) $(shell find $(SRC_DIR) -name "*.java")

test: build
	$(JAVAC) -d $(BUILD_DIR) -cp $(BUILD_DIR) $(shell find $(TEST_DIR) -name "*.java")
	$(JAVA) -cp $(BUILD_DIR) kvlite.tests.TestRunner

clean:
	rm -rf $(BUILD_DIR)

run: build
	$(JAVA) -cp $(BUILD_DIR) kvlite.Main $(ARGS)
