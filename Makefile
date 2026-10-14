# Native development commands for stock_broker. No container runtime is required.
.PHONY: help install start start-feed stop status proto proto-lint proto-format proto-format-check proto-build proto-compile-check ci-lint java-build java-test java-verify web-lint web-build rust-test python-test

help:
	@echo "stock_broker native development commands"
	@echo "  make install       Install all project dependencies"
	@echo "  make start         Start the Java API and Next.js web UI"
	@echo "  make start-feed    Start API, web UI, and Rust feed"
	@echo "  make stop          Stop processes started by main.py"
	@echo "  make status        Show managed process status"
	@echo "  make proto         Run native protobuf checks"
	@echo "  make java-test     Run Java tests"
	@echo "  make web-build     Build the web application"
	@echo "  make rust-test     Run Rust tests"
	@echo "  make python-test   Run Python tests in .venv"

install:
	python main.py install

start:
	python main.py start

start-feed:
	python main.py start --with-feed

stop:
	python main.py stop

status:
	python main.py status

proto: proto-lint proto-format-check proto-build proto-compile-check

proto-lint:
	buf lint proto

proto-format:
	buf format -w proto

proto-format-check:
	buf format -d --exit-code proto

proto-build:
	buf build proto

proto-compile-check:
	python -m grpc_tools.protoc --proto_path=proto --python_out=tmp/proto_gen --grpc_python_out=tmp/proto_gen proto/stockbroker/market/v1/market.proto

ci-lint:
	bash scripts/check-action-pins.sh

java-build:
	cd java && ./mvnw clean compile -B

java-test:
	cd java && ./mvnw test -B

java-verify:
	cd java && ./mvnw verify -B

web-lint:
	cd frontend && npm run lint

web-build:
	cd frontend && npm run build

rust-test:
	cd rust && cargo test --workspace

python-test:
	.venv/Scripts/python -m pytest python/tests
