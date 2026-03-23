# 企业级 AI 知识库系统

包含两个子项目：

- `backend`：Spring Boot 3 + Spring Security JWT + LangChain4j + Milvus + Apache Tika
- `frontend`：Vue 3 + Axios + Vue Router
- `docker-compose.yml`：一键启动 Milvus + 后端 + 前端

默认账号：

- `admin / admin123`
- `user / user123`

快速启动：

```bash
cp .env.example .env
# 填写 APP_LLM_API_KEY
docker compose up --build
```

访问地址：

- 前端：`http://localhost:5173`
- 后端：`http://localhost:8080`
- Milvus：`localhost:19530`
