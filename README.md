# Task Manager

A small Spring Boot task manager, built, tested, containerized and deployed to Amazon EKS
by a Jenkins CI/CD pipeline triggered by a GitHub webhook. **See [SETUP.md](SETUP.md) for the
phased AWS setup and demo steps.**

- REST API for creating, listing, updating, completing and deleting tasks
- A single-page web UI at `/`
- In-memory storage (data resets on restart, so no database is needed)
- Health checks at `/actuator/health/liveness` and `/actuator/health/readiness` for Kubernetes
- `/api/info` shows the deployed version (the pipeline sets it to the git commit SHA)

## Project layout

```
Jenkinsfile                   Jenkins pipeline (build → test → image → ECR → deploy)
ansible/jenkins-setup.yml     Ansible playbook that installs the Jenkins server's toolchain
ansible/deploy.yml            Ansible playbook that applies the Kubernetes manifests and waits for rollout
ansible/verify.yml            Ansible playbook that checks pods are ready and the live URL serves the new version
k8s/                          Kubernetes manifests: namespace.yaml, deployment.yaml, service.yaml
eks/cluster.yaml              eksctl definition of the EKS cluster
Dockerfile                    Runtime image built from the tested JAR
src/                          Java source and tests
```

## Requirements (local)

- Java 21
- Maven 3.9+
- Docker

## Run locally

```bash
mvn test                  # run the 14 unit + integration tests
mvn package               # build target/task-manager.jar (also runs tests)
java -jar target/task-manager.jar
```

Open http://localhost:8080

## Run with Docker

The image packages the already-built JAR, so build it with Maven first:

```bash
mvn package
docker build -t task-manager .
docker run -p 8080:8080 -e APP_VERSION=dev task-manager
```

## API

| Method | Path                     | Purpose               |
|--------|--------------------------|-----------------------|
| GET    | `/api/tasks`             | List all tasks        |
| GET    | `/api/tasks/{id}`        | Get one task          |
| POST   | `/api/tasks`             | Create a task         |
| PUT    | `/api/tasks/{id}`        | Update a task         |
| PATCH  | `/api/tasks/{id}/toggle` | Toggle completed      |
| DELETE | `/api/tasks/{id}`        | Delete a task         |
| GET    | `/api/info`              | App name and version  |
| GET    | `/actuator/health`       | Health check          |

## Environment variables

| Variable      | Default | Purpose                                           |
|---------------|---------|---------------------------------------------------|
| `PORT`        | `8080`  | Port to listen on                                 |
| `APP_VERSION` | `local` | Version shown at `/api/info` and in the UI header |

## Tests

- `TaskServiceTest`: unit tests for the business logic (8 tests)
- `TaskControllerTest`: integration tests that start Spring and call the real API, including the health and info endpoints (6 tests)
