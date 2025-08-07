PORT=8123

run: build
	docker run -p $(PORT):80 --rm --name wetty-service wetty-service

build:
	docker build -t wetty-service .

sample:
	curl -X POST http://localhost:$(PORT)/api/execute \
	  -H "Content-Type: application/json" \
	  -d '{"command": "htop", "timeout": 300}'
	curl http://localhost:$(PORT)/api/status

enter:
	docker exec -ti wetty-service bash
