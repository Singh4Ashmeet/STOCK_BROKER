# Makefile for StockBroker Data Quality

.PHONY: help install test test-cov lint format validate-example

# Default target
help:
	@echo "StockBroker Data Quality - Available targets:"
	@echo "  install      - Install package in development mode"
	@echo "  test         - Run all tests"
	@echo "  test-cov     - Run tests with coverage"
	@echo "  lint         - Run linting (ruff, mypy)"
	@echo "  format       - Format code with ruff"
	@echo "  validate     - Validate example data"
	@echo "  clean        - Clean build artifacts"

# Install in development mode
install:
	pip install -e ".[test]"

# Run tests
test:
	python -m pytest tests/ -v

# Run tests with coverage
test-cov:
	python -m pytest tests/ --cov=stockbroker_dq --cov-report=term-missing --cov-report=html

# Linting
lint:
	ruff check src/ tests/
	mypy src/

# Format code
format:
	ruff format src/ tests/

# Validate example data
validate:
	python -m stockbroker_dq.cli validate ../testdata/recorded_day/ --format summary

# Clean build artifacts
clean:
	rm -rf build/
	rm -rf dist/
	rm -rf *.egg-info/
	rm -rf .pytest_cache/
	rm -rf .coverage
	rm -rf htmlcov/
	rm -rf .mypy_cache/
	rm -rf .ruff_cache/
	find . -type d -name __pycache__ -exec rm -rf {} +
	find . -type f -name "*.pyc" -delete

# Run specific test file
test-validator:
	python -m pytest tests/test_validator.py -v

test-corrupted:
	python -m pytest tests/test_corrupted_files.py -v