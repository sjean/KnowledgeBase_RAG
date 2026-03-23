# 企业级 AI 知识库系统 -- 技术架构文档

> 版本: 1.0.0 | 最后更新: 2026-03-21

---

## 目录

- [1. 项目概述](#1-项目概述)
- [2. 技术栈说明](#2-技术栈说明)
- [3. 系统架构设计](#3-系统架构设计)
  - [3.1 整体架构](#31-整体架构)
  - [3.2 部署架构](#32-部署架构)
  - [3.3 前后端交互](#33-前后端交互)
- [4. 后端详解](#4-后端详解)
  - [4.1 项目结构](#41-项目结构)
  - [4.2 配置管理](#42-配置管理)
  - [4.3 安全认证模块](#43-安全认证模块)
  - [4.4 RAG 知识库引擎](#44-rag-知识库引擎)
  - [4.5 AI Agent 模块](#45-ai-agent-模块)
  - [4.6 REST API 接口规范](#46-rest-api-接口规范)
  - [4.7 数据模型](#47-数据模型)
  - [4.8 异常处理策略](#48-异常处理策略)
- [5. 前端详解](#5-前端详解)
  - [5.1 项目结构](#51-项目结构)
  - [5.2 路由与状态管理](#52-路由与状态管理)
  - [5.3 API 通信层](#53-api-通信层)
  - [5.4 页面组件](#54-页面组件)
  - [5.5 UI 设计体系](#55-ui-设计体系)
- [6. 核心业务流程](#6-核心业务流程)
  - [6.1 文档上传与向量化](#61-文档上传与向量化)
  - [6.2 RAG 智能问答](#62-rag-智能问答)
  - [6.3 JWT 认证全流程](#63-jwt-认证全流程)
- [7. 关键算法与实现细节](#7-关键算法与实现细节)
  - [7.1 文本分块算法](#71-文本分块算法)
  - [7.2 向量检索策略](#72-向量检索策略)
  - [7.3 Agent 工具调用追踪机制](#73-agent-工具调用追踪机制)
  - [7.4 聊天缓存策略](#74-聊天缓存策略)
- [8. 依赖关系图](#8-依赖关系图)
- [9. 维护要点与改进建议](#9-维护要点与改进建议)

---

## 1. 项目概述

本系统是一个**企业级 AI 知识库系统**（Enterprise AI Knowledge Base），采用前后端分离架构，集成了 RAG（Retrieval-Augmented Generation，检索增强生成）技术与 AI Agent 能力。用户可上传文档（PDF/Word/TXT），系统自动解析、分块、向量化后存入 Milvus 向量数据库，在对话时通过语义检索获取相关知识片段，结合 LLM 生成精准回答。

### 核心能力

| 能力 | 描述 |
|---|---|
| **文档知识管理** | 支持 PDF/DOC/DOCX/TXT 文档上传，自动解析与向量化存储 |
| **RAG 智能问答** | 基于用户文档库的上下文感知问答，返回答案及来源引用 |
| **AI Agent 增强** | LLM 可自主决定调用工具（文档统计、系统状态查询） |
| **角色权限控制** | ADMIN 可检索全部文档，USER 仅检索自身文档 |
| **JWT 无状态认证** | 基于 JWT Token 的无状态认证，适合水平扩展 |

### 默认账号

| 用户名 | 密码 | 角色 |
|---|---|---|
| `admin` | `admin123` | ADMIN |
| `user` | `user123` | USER |

---

## 2. 技术栈说明

### 2.1 后端技术栈

| 技术 | 版本 | 用途 |
|---|---|---|
| **Java** | 17 | 运行时环境 |
| **Spring Boot** | 3.3.2 | 应用框架 |
| **Spring Security** | 3.3.2 | 认证授权 |
| **Spring Data JPA** | 3.3.2 | ORM / 数据访问 |
| **LangChain4j** | 0.35.0 | LLM 对接 + AI Agent 编排 |
| **Milvus SDK** | 2.4.2 | 向量数据库客户端 |
| **Apache Tika** | 2.9.2 | 多格式文档解析 |
| **JJWT** | 0.12.6 | JWT Token 签发与解析 |
| **H2 Database** | (runtime) | 嵌入式关系数据库（元数据存储） |
| **Lombok** | (optional) | 代码简化 |

### 2.2 前端技术栈

| 技术 | 版本 | 用途 |
|---|---|---|
| **Vue** | 3.4.31 | 前端框架（Composition API） |
| **Vue Router** | 4.4.0 | 客户端路由 |
| **Axios** | 1.7.2 | HTTP 请求 |
| **Vite** | 5.3.4 | 构建工具 |

### 2.3 基础设施技术栈

| 技术 | 版本 | 用途 |
|---|---|---|
| **Milvus** | v2.4.13 | 向量数据库（Docker 部署） |
| **etcd** | v3.5.16 | Milvus 元数据存储 |
| **MinIO** | 2024.07 | Milvus 对象存储 |
| **Nginx** | 1.27-alpine | 前端静态托管 + API 反向代理 |
| **Docker Compose** | - | 容器编排 |

### 2.4 LLM 配置

| 配置项 | 值 | 说明 |
|---|---|---|
| API 提供商 | 智谱 AI (BigModel) | 兼容 OpenAI 接口格式 |
| Chat 模型 | `glm-5` | 对话生成模型 |
| Embedding 模型 | `embedding-3` | 文本向量化模型 |
| 向量维度 | 2048 | 嵌入向量维度 |

---

## 3. 系统架构设计

### 3.1 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        前端 (Vue 3 SPA)                         │
│  ┌──────────┐  ┌──────────────┐  ┌───────────────────────────┐  │
│  │ Login.vue│  │  Chat.vue    │  │   Axios + 拦截器          │  │
│  │ 登录页面  │  │  聊天主页面   │  │   (JWT自动注入/401处理)   │  │
│  └──────────┘  └──────────────┘  └──────────┬────────────────┘  │
└─────────────────────────────────────────────┼───────────────────┘
                                              │ HTTP (JSON)
                                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                   Nginx (反向代理 + 静态托管)                    │
│               /api/*  ──strip──>  backend:8080/*                │
│               /*      ──serve──>  Vue SPA (dist/)               │
└─────────────────────────────────────────────┬───────────────────┘
                                              │
                                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                   后端 (Spring Boot 3)                           │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │              Security Layer (JWT Filter)                 │    │
│  │   JwtAuthenticationFilter → SecurityContext → Controller │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
│  ┌──────────┐  ┌──────────────┐  ┌────────────────────────┐    │
│  │  Auth    │  │  Chat        │  │  File Upload            │    │
│  │Controller│  │  Controller  │  │  Controller             │    │
│  └────┬─────┘  └──────┬───────┘  └──────────┬─────────────┘    │
│       │               │                      │                   │
│  ┌────▼─────┐  ┌──────▼───────┐  ┌──────────▼─────────────┐    │
│  │ AuthService│ │ ChatService  │  │ DocumentService         │    │
│  └──────────┘  │  ├─ RagService│  │  ├─ TikaParserUtil      │    │
│                │  └─ AgentSvc  │  │  ├─ TextChunker         │    │
│                └──────────────┘  │  ├─ EmbeddingService     │    │
│                                   │  └─ MilvusVectorService  │    │
│                                   └─────────────────────────┘    │
│                                                                  │
│  ┌─────────────────────┐  ┌──────────────────────────────────┐  │
│  │  H2 Database         │  │  LangChain4j AI Agent            │  │
│  │  (用户/文档元数据)    │  │  (KnowledgeAssistant + Tools)    │  │
│  └─────────────────────┘  └──────────────────────────────────┘  │
└─────────────────────────────────────────────┬───────────────────┘
                                              │
                                    ┌─────────┴──────────┐
                                    ▼                    ▼
                          ┌─────────────────┐  ┌──────────────┐
                          │  Milvus          │  │  智谱 GLM API │
                          │  向量数据库       │  │  (LLM/Embed) │
                          │  (知识分块+向量)  │  │              │
                          └─────────────────┘  └──────────────┘
```

### 3.2 部署架构

系统通过 Docker Compose 一键编排，包含 **5 个服务**：

```
┌──────────────────────────────────────────────────────────┐
│                   Docker Compose                          │
│                                                           │
│  ┌──────────┐                                             │
│  │  etcd    │──┐                                          │
│  │  (元数据) │  │                                          │
│  └──────────┘  │                                          │
│  ┌──────────┐  ├──┐                                       │
│  │  MinIO   │──┘  │                                       │
│  │ (对象存储)│     │                                       │
│  └──────────┘     ▼                                       │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  │
│  │  Milvus  │  │ backend  │←─│ frontend │  │          │  │
│  │  :19530  │←─│  :8080   │  │  :5173   │  │          │  │
│  └──────────┘  └──────────┘  └──────────┘  │          │  │
│                                            │          │  │
│  Volumes: etcd_data, minio_data,            │          │  │
│           milvus_data, backend_data         └──────────┘  │
└──────────────────────────────────────────────────────────┘
```

**服务依赖链**：`etcd + MinIO` → `Milvus` → `backend` → `frontend`

所有服务均配置了健康检查（healthcheck），确保依赖就绪后才启动下游服务。

### 3.3 前后端交互

| 环境 | 前端地址 | API 基础路径 | 代理方式 |
|---|---|---|---|
| **开发** | `http://localhost:5173` | `/api` | Vite proxy → `http://localhost:8080`（保留 `/api` 前缀） |
| **生产** | `http://localhost:5173` | `/api` | Nginx → `http://backend:8080`（去除 `/api` 前缀） |

> **注意**：开发环境 Vite 代理保留 `/api` 前缀，Nginx 生产代理通过 `proxy_pass` 末尾 `/` 去除前缀。后端 Controller 实际路径为 `/auth/*`、`/chat/*`、`/file/*`，不含 `/api`。

---

## 4. 后端详解

### 4.1 项目结构

```
backend/src/main/java/com/example/aikb/
├── AiKnowledgeBaseApplication.java   # 启动类 (@SpringBootApplication, @EnableCaching)
├── agent/                            # AI Agent 包
│   ├── AgentService.java             # Agent 核心服务，编排 LLM + 工具调用
│   ├── AssistantTools.java           # @Tool 工具集（3个可调用工具）
│   ├── KnowledgeAssistant.java       # LangChain4j AI 助手接口
│   └── ToolTrackingHolder.java       # ThreadLocal 工具调用追踪器
├── config/                           # 配置包
│   ├── AppConfig.java                # CORS 跨域配置
│   ├── AppProperties.java            # @ConfigurationProperties 属性绑定
│   └── LlmConfig.java                # OpenAiChatModel / OpenAiEmbeddingModel Bean
├── controller/                       # REST 控制器
│   ├── AuthController.java           # POST /auth/login, GET /auth/me
│   ├── ChatController.java           # POST /chat/ask
│   ├── FileController.java           # POST /file/upload
│   └── GlobalExceptionHandler.java   # @RestControllerAdvice 全局异常处理
├── dto/                              # 数据传输对象 (Java Record)
│   ├── ChatRequest.java              # { question }
│   ├── ChatResponse.java             # { answer, sources, toolUsed }
│   ├── LoginRequest.java             # { username, password }
│   ├── LoginResponse.java            # { token, userId, username, role }
│   ├── SourceItem.java               # { fileName, chunkId, content }
│   ├── UploadResponse.java           # { documentId, fileName, chunkCount }
│   └── UserInfoResponse.java         # { userId, username, role }
├── entity/                           # JPA 实体
│   ├── DocumentRecord.java           # 文档记录（documents 表）
│   ├── Role.java                     # 角色枚举: ADMIN, USER
│   └── UserAccount.java              # 用户账户（users 表）
├── repository/                       # 数据访问层
│   ├── DocumentRecordRepository.java # JpaRepository
│   └── UserAccountRepository.java    # JpaRepository
├── security/                         # 安全模块
│   ├── CustomUserDetailsService.java # UserDetailsService 实现
│   ├── JwtAuthenticationFilter.java  # OncePerRequestFilter
│   ├── JwtService.java               # JWT 签发 / 解析
│   ├── SecurityConfig.java           # Spring Security 配置
│   └── UserPrincipal.java            # 认证主体 Record
├── service/                          # 业务服务
│   ├── AuthService.java              # 登录认证
│   ├── ChatService.java              # RAG + Agent 聊天（带缓存）
│   ├── DocumentService.java          # 文档上传处理
│   ├── EmbeddingService.java         # 文本向量化
│   ├── MilvusVectorService.java      # Milvus CRUD + 索引
│   ├── RagService.java               # RAG 检索（embedding → 向量搜索）
│   └── UserBootstrapService.java     # 启动时初始化默认用户
└── util/                             # 工具类
    ├── SecurityUtils.java            # SecurityContext 工具
    ├── TextChunker.java              # 文本分块
    └── TikaParserUtil.java           # Apache Tika 文件解析
```

**包数量**：10 个 | **Java 文件数量**：39 个 | **DTO 全部使用 Java Record**

### 4.2 配置管理

配置采用 `@ConfigurationProperties` 类型安全绑定，分为 4 个子命名空间：

```yaml
app:
  jwt:
    secret: <JWT签名密钥>
    expiration-hours: 12
  rag:
    top-k: 3
    chunk-size: 500
    chunk-overlap: 50
  llm:
    api-key: <API密钥>
    base-url: https://open.bigmodel.cn/api/paas/v4/
    model-name: glm-5
    embedding-model: embedding-3
    log-requests: true
    log-responses: true
  milvus:
    host: 127.0.0.1
    port: 19530
    collection-name: kb_chunks
    dimension: 2048
    index-type: IVF_FLAT
    metric-type: COSINE
    nlist: 1024
    nprobe: 10
```

所有配置均可通过 **环境变量** 覆盖，例如 `APP_JWT_SECRET`、`APP_LLM_API_KEY` 等，便于容器化部署。

### 4.3 安全认证模块

#### JWT 认证流程

```
                    ┌──────────────┐
                    │  客户端请求    │
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐
                    │ JwtAuthFilter │  (OncePerRequestFilter)
                    │ 提取 Bearer   │
                    └──────┬───────┘
                           │
                 ┌─────────▼──────────┐
                 │ Header 中有 Token?  │
                 └──┬─────────────┬──┘
                   NO            YES
                    │             │
                    ▼             ▼
               继续 Filter    ┌─────────────┐
                              │ JwtService   │
                              │ 解析 Claims  │
                              └──────┬──────┘
                                     │
                              ┌──────▼──────┐
                              │ 创建        │
                              │ UserPrincipal│
                              │ (userId,    │
                              │  username,  │
                              │  role)      │
                              └──────┬──────┘
                                     │
                              ┌──────▼──────┐
                              │ 设置         │
                              │ SecurityCtx  │
                              └──────┬──────┘
                                     │
                              ┌──────▼──────┐
                              │ Controller  │
                              │ 获取当前用户  │
                              └─────────────┘
```

#### SecurityConfig 要点

| 配置项 | 值 | 说明 |
|---|---|---|
| CSRF | 禁用 | 无状态 REST API |
| Session | STATELESS | 纯 JWT，无 Session |
| 放行路径 | `/auth/login`, `/error`, `/h2-console/**` | 无需认证 |
| 其他路径 | `authenticated()` | 均需认证 |
| 密码编码 | BCryptPasswordEncoder | BCrypt 哈希 |
| 方法级安全 | `@EnableMethodSecurity` | 支持 `@PreAuthorize` |

#### 启动初始化 (UserBootstrapService)

`CommandLineRunner` 在应用启动时自动执行，通过 `UserAccountRepository.findByUsername()` 检查并创建默认用户（如果不存在）。密码使用 `BCryptPasswordEncoder` 加密后存储。

### 4.4 RAG 知识库引擎

#### 组件协作关系

```
                    DocumentService (文档上传)
                    ┌───────────────────────────┐
                    │ 1. TikaParserUtil.parse()  │ → 解析文件为纯文本
                    │ 2. TextChunker.split()     │ → 文本分块
                    │ 3. EmbeddingService.embed()│ → 向量化
                    │ 4. MilvusVectorService     │ → 存入 Milvus
                    │    .storeChunks()          │
                    │ 5. DocumentRecordRepo      │ → 存元数据
                    │    .save()                 │
                    └───────────────────────────┘

                    RagService (知识检索)
                    ┌───────────────────────────┐
                    │ 1. EmbeddingService.embed()│ → 问题向量化
                    │ 2. MilvusVectorService     │ → 向量相似度搜索
                    │    .search()               │
                    │ 3. 过滤 (ADMIN/USER)       │ → 权限隔离
                    │ 4. 拼接上下文               │ → 返回 context
                    └───────────────────────────┘
```

#### Milvus 集合结构 (kb_chunks)

| 字段名 | 类型 | 说明 |
|---|---|---|
| `id` | Int64 | 自增主键 |
| `user_id` | Int64 | 所属用户 ID（用于权限隔离） |
| `chunk_id` | VarChar(256) | 分块唯一标识 (UUID) |
| `file_name` | VarChar(256) | 来源文件名 |
| `content` | VarChar(8192) | 分块文本内容 |
| `embedding` | FloatVector(2048) | 文本向量 |

#### MilvusVectorService 关键设计

- **启动时自动管理集合**：检测集合是否存在，校验维度，不存在则创建（含 Schema + Index）
- **索引类型**：IVF_FLAT（nlist=1024），适合中等规模数据集
- **相似度度量**：COSINE（余弦相似度）
- **搜索参数**：nprobe=10（搜索时探测 10 个聚类）
- **关闭时自动断连**：通过 `@PreDestroy` 释放连接

### 4.5 AI Agent 模块

基于 LangChain4j AiServices 框架实现：

```java
// KnowledgeAssistant 接口定义
@SystemMessage("""
    你是企业知识库AI助手...
    如果用户询问文档相关统计信息，请调用相应工具查询。
    """)
public interface KnowledgeAssistant {
    String chat(@UserMessage String message);
}
```

#### 可调用工具 (AssistantTools)

| 工具方法 | 描述 | 参数 | 权限 |
|---|---|---|---|
| `queryCurrentUserDocumentCount(Long userId)` | 查询指定用户文档数量 | userId | 所有角色 |
| `queryTotalDocumentCount()` | 查询系统总文档数量 | 无 | 所有角色 |
| `currentSystemStatus()` | 获取系统运行状态（时间、内存、Java 版本等） | 无 | 所有角色 |

#### 工具调用追踪 (ToolTrackingHolder)

使用 `ThreadLocal<String>` 追踪 Agent 是否调用了工具。当 LangChain4j 执行工具方法时，ToolTrackingHolder 记录工具名称，`ChatService` 在 Agent 执行完成后读取并返回给前端展示。

```java
public final class ToolTrackingHolder {
    private static final ThreadLocal<String> TOOL_NAME = new ThreadLocal<>();

    public static void set(String name) { TOOL_NAME.set(name); }
    public static String get() { return TOOL_NAME.get(); }
    public static void clear() { TOOL_NAME.remove(); }
}
```

### 4.6 REST API 接口规范

#### 认证接口 `/auth`

| 方法 | 路径 | 请求体 | 响应体 | 认证 |
|---|---|---|---|---|
| POST | `/auth/login` | `{ "username": "", "password": "" }` | `{ "token": "jwt...", "userId": 1, "username": "admin", "role": "ADMIN" }` | 否 |
| GET | `/auth/me` | - | `{ "userId": 1, "username": "admin", "role": "ADMIN" }` | 是 |

#### 聊天接口 `/chat`

| 方法 | 路径 | 请求体 | 响应体 | 认证 |
|---|---|---|---|---|
| POST | `/chat/ask` | `{ "question": "公司请假制度是什么？" }` | `{ "answer": "...", "sources": [...], "toolUsed": null }` | 是 |

`ChatResponse.sources` 数组元素结构：
```json
{ "fileName": "test-doc.txt", "chunkId": "uuid", "content": "员工请假需要提前一天提交申请" }
```

#### 文件接口 `/file`

| 方法 | 路径 | 请求体 | 响应体 | 认证 |
|---|---|---|---|---|
| POST | `/file/upload` | `multipart/form-data` (file) | `{ "documentId": 1, "fileName": "doc.pdf", "chunkCount": 5 }` | 是 |

**支持文件类型**：`.pdf`, `.doc`, `.docx`, `.txt`
**文件大小限制**：20MB

### 4.7 数据模型

#### H2 关系型数据

**users 表** (`UserAccount`)：

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | 主键 |
| username | VARCHAR(64) | NOT NULL, UNIQUE | 用户名 |
| password | VARCHAR | NOT NULL | BCrypt 加密密码 |
| role | VARCHAR(16) | NOT NULL | ADMIN / USER |

**documents 表** (`DocumentRecord`)：

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | 主键 |
| user_id | BIGINT | NOT NULL, FK | 所属用户 |
| file_name | VARCHAR(255) | NOT NULL | 文件名 |
| chunk_count | INT | NOT NULL | 分块数量 |
| created_at | TIMESTAMP | NOT NULL | 上传时间 |

### 4.8 异常处理策略

`GlobalExceptionHandler` 统一拦截并转换异常为标准 JSON 响应：

| 异常类型 | HTTP 状态码 | 响应格式 |
|---|---|---|
| `IllegalArgumentException` | 400 Bad Request | `{ "message": "错误描述" }` |
| `MethodArgumentNotValidException` | 400 Bad Request | `{ "message": "Request validation failed" }` |
| `Exception` (兜底) | 500 Internal Server Error | `{ "message": "异常消息" }` |

---

## 5. 前端详解

### 5.1 项目结构

```
frontend/
├── Dockerfile              # 多阶段构建 (Node 20 + Nginx)
├── nginx.conf              # Nginx 反向代理配置
├── vite.config.js          # Vite 配置 (端口5173, /api代理)
├── package.json            # 依赖管理
├── index.html              # SPA 入口
└── src/
    ├── main.js             # 应用入口 (createApp + router)
    ├── App.vue             # 根组件 (router-view + CSS变量)
    ├── api/
    │   ├── http.js         # Axios 实例 + 请求/响应拦截器
    │   ├── auth.js         # login(), me()
    │   ├── chat.js         # askQuestion()
    │   └── file.js         # uploadFile()
    ├── router/
    │   └── index.js        # 路由定义 + 全局守卫
    └── views/
        ├── Login.vue       # 登录页
        └── Chat.vue        # 聊天主页面
```

### 5.2 路由与状态管理

#### 路由配置

| 路径 | 组件 | 说明 |
|---|---|---|
| `/` | - | 重定向到 `/chat` |
| `/login` | `Login.vue` | 登录页面 |
| `/chat` | `Chat.vue` | 聊天主页面 |

**路由模式**：HTML5 History (`createWebHistory()`)

#### 全局路由守卫

```javascript
router.beforeEach((to) => {
  const token = localStorage.getItem('token')
  if (to.path !== '/login' && !token) return '/login'
  if (to.path === '/login' && token) return '/chat'
})
```

#### 状态管理

项目**未使用 Pinia/Vuex**，采用轻量方案：

| 机制 | 用途 |
|---|---|
| `localStorage.token` | JWT 令牌持久化 |
| 组件内 `reactive()`/`ref()` | 页面临时状态 |
| 路由守卫 | 登录状态校验 |
| Axios 拦截器 | 自动附加 Token / 401 处理 |

### 5.3 API 通信层

#### Axios 实例 (http.js)

- **baseURL**：`VITE_API_BASE_URL` 环境变量，默认 `/api`
- **请求拦截器**：自动注入 `Authorization: Bearer <token>`
- **响应拦截器**：401 → 清除 token → 跳转 `/login`

#### API 调用汇总

| 模块 | 函数 | 方法 | 端点 |
|---|---|---|---|
| auth.js | `login(payload)` | POST | `/api/auth/login` |
| auth.js | `me()` | GET | `/api/auth/me` |
| chat.js | `askQuestion(question)` | POST | `/api/chat/ask` |
| file.js | `uploadFile(file)` | POST | `/api/file/upload` |

### 5.4 页面组件

#### Login.vue

- 左侧 Hero 区域 + 右侧登录卡片
- 默认预填 `user / user123`
- 密码框回车触发提交
- 900px 以下单列布局

#### Chat.vue

- **左侧边栏** (320px)：品牌标题、用户信息、文件上传、退出登录
- **右侧主区域**：消息列表（可滚动） + 底部输入区域
- 用户消息：右对齐，青蓝渐变
- AI 消息：左对齐，白色半透明，可展示引用来源 (`sources`) 和工具调用 (`toolUsed`)
- 快捷键：`Ctrl+Enter` 发送
- 960px 以下侧边栏折叠

#### 消息数据结构

```javascript
{
  role: 'user' | 'assistant',
  answer: string,
  sources: [{ chunkId, fileName, content }],  // AI 回复时
  toolUsed: string | null                      // AI 回复时
}
```

### 5.5 UI 设计体系

通过 CSS 变量定义全局主题：

| 变量 | 值 | 用途 |
|---|---|---|
| `--bg` | `linear-gradient(135deg, #f3efe4, #e0f0ea, #d8e3f8)` | 页面背景 |
| `--panel` | `rgba(255,255,255,0.82)` | 面板背景（毛玻璃） |
| `--accent` | `#0f766e` | 主题色 (青色) |
| `--accent-2` | `#1d4ed8` | 辅助色 (蓝色) |
| `--danger` | `#c2410c` | 错误色 (橙红) |
| `--text` | `#1f2937` | 主文本 |
| `--muted` | `#5b6472` | 次要文本 |

设计特点：毛玻璃效果 (`backdrop-filter: blur`)、CSS Grid 布局、渐变色、全手写 CSS（无 UI 组件库）。

---

## 6. 核心业务流程

### 6.1 文档上传与向量化

```
用户选择文件 → FileController.upload()
    │
    ▼
DocumentService.upload(userId, file)
    │
    ├─ 1. 验证文件类型 (PDF/DOC/DOCX/TXT)
    │
    ├─ 2. TikaParserUtil.parseText(file)
    │     └─ Apache Tika 解析为纯文本
    │
    ├─ 3. TextChunker.split(text, chunkSize=500, overlap=50)
    │     └─ 滑动窗口分块，生成 List<TextChunk>
    │
    ├─ 4. EmbeddingService.embed(chunks)
    │     └─ 调用智谱 embedding-3 API，批量向量化
    │
    ├─ 5. MilvusVectorService.storeChunks(chunks)
    │     └─ 批量插入 Milvus (content + embedding + userId + fileName + chunkId)
    │
    ├─ 6. DocumentRecordRepository.save()
    │     └─ 保存文档元数据到 H2 (fileName, chunkCount, userId)
    │
    └─ 7. 返回 UploadResponse { documentId, fileName, chunkCount }
```

### 6.2 RAG 智能问答

```
用户输入问题 → ChatController.ask(principal, request)
    │
    ▼
ChatService.chat(principal, question)  [@Cacheable(key="userId:question")]
    │
    ├─ 步骤1: 知识检索 ── RagService.retrieve(userId, isAdmin, question)
    │   │
    │   ├─ EmbeddingService.embed(question)
    │   │   └─ 问题文本 → 向量
    │   │
    │   ├─ MilvusVectorService.search(embedding, topK=3)
    │   │   └─ 向量相似度搜索
    │   │
    │   ├─ 权限过滤:
    │   │   ├─ ADMIN: 搜索所有用户的文档
    │   │   └─ USER: 仅搜索 user_id == userId 的文档
    │   │
    │   └─ 返回 knowledgeContext (拼接 top-3 相关片段)
    │
    ├─ 步骤2: Agent 推理 ── AgentService.ask(principal, question, context)
    │   │
    │   ├─ ToolTrackingHolder.clear()
    │   │
    │   ├─ 构建 Prompt:
    │   │   "用户: {username} (角色: {role})
    │   │    知识库上下文: {context}
    │   │    问题: {question}"
    │   │
    │   ├─ KnowledgeAssistant.chat(prompt)
    │   │   └─ LangChain4j AiServices 调用 GLM-5
    │   │       ├─ 可能调用 AssistantTools 中的工具
    │   │       └─ 返回最终回答
    │   │
    │   └─ 返回 { answer, toolUsed }
    │
    └─ 返回 ChatResponse { answer, sources, toolUsed }
```

### 6.3 JWT 认证全流程

```
登录请求 → AuthController.login(request)
    │
    ├─ AuthenticationManager.authenticate(username, password)
    │   └─ CustomUserDetailsService.loadUserByUsername()
    │       └─ UserAccountRepository.findByUsername()
    │       └─ DaoAuthenticationProvider 验证密码
    │
    ├─ JwtService.generateToken(user)
    │   └─ 包含: sub=username, claim(userId), claim(role), iat, exp(12h)
    │
    └─ 返回 LoginResponse { token, userId, username, role }

后续请求:
    → JwtAuthenticationFilter
        → 从 Authorization: Bearer <token> 提取
        → JwtService.parse(token) → Claims
        → 创建 UserPrincipal(userId, username, role)
        → 设置 SecurityContextHolder
    → Controller 通过 SecurityUtils.currentUser() 获取
```

---

## 7. 关键算法与实现细节

### 7.1 文本分块算法 (TextChunker)

采用**滑动窗口分块**策略：

```
参数:
  chunkSize = 500 字符
  overlap = 50 字符

算法:
  step = chunkSize - overlap = 450
  chunks = []
  for i = 0; i < text.length; i += step:
      chunk = text[i : i + chunkSize]
      chunks.add(chunk)

示例 (文本长度 1000):
  chunk[0]: [0, 500)
  chunk[1]: [450, 950)
  chunk[2]: [900, 1000)
```

**设计考量**：
- 重叠区保证上下文连续性，避免语义被截断
- 固定字符长度而非 Token 数量，实现简单
- 适合中英文混合文档

### 7.2 向量检索策略

```
检索参数:
  向量维度: 2048
  索引类型: IVF_FLAT (nlist=1024)
  度量方式: COSINE (余弦相似度)
  top-k: 3
  nprobe: 10

流程:
  1. 问题文本 → embedding-3 API → 2048维向量
  2. Milvus ANN 搜索 (IVF_FLAT + COSINE)
  3. 根据 user_id 过滤 (ADMIN 跳过过滤)
  4. 返回 top-3 最相关文档片段
  5. 拼接为 knowledgeContext 文本
```

**IVF_FLAT 选择理由**：
- 将向量空间划分为 1024 个聚类 (nlist)
- 搜索时探测 10 个最近聚类 (nprobe)
- 在召回率与速度之间取得平衡
- 适合中等规模数据集（< 100 万条）

### 7.3 Agent 工具调用追踪机制

LangChain4j 通过 `@Tool` 注解将普通方法注册为 Agent 可调用的工具。追踪机制如下：

```
1. ChatService 收到问题
2. ToolTrackingHolder.clear()  → 清除旧状态
3. 调用 KnowledgeAssistant.chat(prompt)
   ├─ LLM 分析问题
   ├─ 如果需要，LLM 输出 tool_call 指令
   ├─ LangChain4j 框架调用对应的 @Tool 方法
   ├─ @Tool 方法内部执行 ToolTrackingHolder.set("工具名")
   └─ LLM 根据工具返回结果生成最终回答
4. ChatService 读取 ToolTrackingHolder.get()
5. 返回 { answer, toolUsed: "工具名" 或 null }
```

### 7.4 聊天缓存策略

```java
@Cacheable(value = "chatCache", key = "#principal.userId + ':' + #question")
public ChatResponse chat(UserPrincipal principal, String question)
```

- **缓存类型**：Spring Simple Cache（内存）
- **缓存 Key**：`userId:question`
- **生效范围**：同一用户相同问题直接返回缓存结果
- **注意**：Simple Cache 无过期策略，重启后清空，适合开发/小规模使用

---

## 8. 依赖关系图

### 后端 Service 层依赖

```
AuthController  → AuthService → UserAccountRepository

ChatController  → ChatService → RagService → EmbeddingService
                │             │           → MilvusVectorService
                │             └→ AgentService → KnowledgeAssistant (interface)
                │                              → AssistantTools → DocumentRecordRepository
                │                              → ToolTrackingHolder
                └→ SecurityUtils

FileController  → DocumentService → TikaParserUtil
                │                 → TextChunker
                │                 → EmbeddingService
                │                 → MilvusVectorService
                │                 → DocumentRecordRepository
                └→ SecurityUtils
```

### Security 组件依赖

```
SecurityConfig
  → JwtAuthenticationFilter → JwtService → AppProperties.Jwt
  → CustomUserDetailsService → UserAccountRepository
  → DaoAuthenticationProvider + BCryptPasswordEncoder
```

### Config 组件依赖

```
AppConfig → AppProperties (激活 @ConfigurationProperties)
LlmConfig → AppProperties (创建 OpenAiChatModel + OpenAiEmbeddingModel)
```

---

## 9. 维护要点与改进建议

### 9.1 运维要点

| 要点 | 说明 |
|---|---|
| **JWT 密钥轮换** | 生产环境务必修改 `app.jwt.secret`，建议定期轮换 |
| **API 密钥安全** | `APP_LLM_API_KEY` 必须通过环境变量注入，切勿提交代码 |
| **Milvus 数据备份** | 定期备份 `milvus_data` 卷中的向量数据 |
| **H2 数据备份** | 定期备份 `backend_data` 卷中的 `aikb.mv.db` 文件 |
| **日志监控** | LLM 请求/响应日志已开启 (`log-requests`, `log-responses`)，便于排查 |
| **健康检查** | 所有 Docker 服务均配置健康检查，确保依赖就绪 |

### 9.2 改进建议

| 领域 | 当前状态 | 建议 |
|---|---|---|
| **缓存** | Simple 内存缓存 | 引入 Redis 替代，支持过期策略和集群共享 |
| **数据库** | H2 嵌入式 | 生产环境迁移至 PostgreSQL/MySQL |
| **前端状态** | 无状态管理库 | 引入 Pinia 管理全局状态 |
| **前端类型** | 纯 JavaScript | 迁移至 TypeScript 增强类型安全 |
| **前端 UI** | 手写 CSS | 引入组件库（Element Plus / Ant Design Vue）提升开发效率 |
| **测试** | 无测试覆盖 | 补充单元测试（JUnit 5 + Mockito）和集成测试 |
| **API 代理** | 开发/生产不一致 | 统一代理策略，开发环境也去除 `/api` 前缀 |
| **向量搜索** | IVF_FLAT | 大规模数据场景迁移至 HNSW 索引 |
| **文档处理** | 仅支持4种格式 | 扩展支持 PPT/Excel/Markdown/HTML |
| **分块策略** | 固定字符窗口 | 引入语义分块（按段落/标题），提升检索精度 |
| **对话历史** | 无多轮对话 | 引入 ConversationMemory，支持多轮上下文 |
| **文件大小** | 20MB 上限 | 大文件场景引入流式处理 |
| **错误处理** | 全局兜底 | 细化业务异常类型，提供更精准的错误信息 |
| **日志** | 标准输出 | 引入结构化日志（Logback JSON / ELK） |
| **安全** | 无限流 | 引入 Rate Limiter 防止 API 滥用 |
