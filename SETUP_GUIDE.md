# 赛博小镇 - 安装与启动指南

这份指南按当前仓库里的真实结构整理，适用于现在这套链路：

```text
Godot Client -> Java Gateway:8080 -> RabbitMQ / Redis / MySQL -> Python AI Backend:8000
```

## 系统要求

- Godot `4.6` 左右版本
- Python `3.10+`
- Java `17`
- MySQL `8.x`
- Redis `6+`
- RabbitMQ `3.x`

## 项目结构

```text
Helloagents-AI-Town/
├── backend/
├── helloagents-ai-town/
├── java-gateway/
└── SETUP_GUIDE.md
```

## 端口约定

- Godot 访问 Java 网关：`http://localhost:8080`
- Godot WebSocket：`ws://localhost:8080/ws/chat?playerId=player_1`
- Python FastAPI：`http://localhost:8000`
- MySQL：`3307`
- Redis：`6379`
- RabbitMQ：`5672`

## 第一步：准备基础服务

当前 `java-gateway/src/main/resources/application.yml` 除 MySQL 密码外，使用的是下面这些默认值：

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:mysql://localhost:3307/ai_town_db?useSSL=false&serverTimezone=UTC&characterEncoding=utf-8&allowPublicKeyRetrieval=true
    username: root
    password: ${MYSQL_PASSWORD}
  data:
    redis:
      host: localhost
      port: 6379
  rabbitmq:
    host: localhost
    port: 5672
```

先在项目根目录创建本地环境文件，并填写 `MYSQL_PASSWORD`（`.env` 已被 Git 忽略）：

```bash
cp .env.example .env
# 编辑 .env，设置 MYSQL_PASSWORD
```

推荐直接在项目根目录启动完整基础设施，Docker Compose 会自动读取 `.env`：

```bash
docker compose up -d
```

它会启动 MySQL、Redis 和 RabbitMQ，并在全新 MySQL 数据卷中执行
`docker/mysql-init/001_init.sql` 与 `002_reliable_chat.sql`。如果复用升级前的数据卷，需手动执行
`002_reliable_chat.sql`。

如果不使用 Docker，请先确保本机已经准备好：

1. MySQL，并创建数据库 `ai_town_db`
2. Redis
3. RabbitMQ

如果使用已有 MySQL，`MYSQL_PASSWORD` 必须与该数据库的实际密码一致；修改 `.env` 不会更改已有数据卷中的密码。其他连接参数可通过 `MYSQL_URL` 和 `MYSQL_USERNAME` 环境变量设置。

## 第二步：配置 Python 推理服务

进入 [backend](/Users/cuihaoyu/Desktop/集合/hello-agents/Helloagents-AI-Town/backend)：

```bash
cd backend
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

Windows 下激活方式：

```bash
venv\Scripts\activate
```

### 环境变量

当前代码实际会读取这些关键变量：

```env
GROQ_API_KEY=your-groq-api-key
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_DB=0
```

说明：

- `langchain_agent.py` 当前默认使用 Groq 的 OpenAI 兼容接口
- 没有 `GROQ_API_KEY` 时，推理服务大概率无法正常生成回复
- 仓库里的 [backend/.env.example](/Users/cuihaoyu/Desktop/集合/hello-agents/Helloagents-AI-Town/backend/.env.example) 还保留了旧示例内容，不能完全当作当前运行配置

如果你使用 `.env`，把它放在 [backend/.env](/Users/cuihaoyu/Desktop/集合/hello-agents/Helloagents-AI-Town/backend/.env)。

## 第三步：启动 Python 服务

### 1. 启动 FastAPI 推理接口

在 `backend` 目录执行：

```bash
python main.py
```

启动后可访问：

- `http://localhost:8000/health`
- `http://localhost:8000/docs`

### 2. 启动 RabbitMQ 消费端

另开一个终端，在 `backend` 目录执行：

```bash
python mq_consumer.py
```

这一步不能省。

原因是：

- `main.py` 负责对外提供 `/agent/react`
- `mq_consumer.py` 负责消费聊天任务队列并把结果回写 Redis / RabbitMQ
- 只启动 `main.py` 时，完整聊天链路不会跑通

## 第四步：启动 Java Gateway

Java 网关源码位于 `java-gateway/src/main/java`，先测试并打包：

```bash
cd java-gateway
set -a; . ../.env; set +a
./mvnw test
./mvnw spring-boot:run
```

默认监听：

- `http://localhost:8080`

如果你后面补全了源码，再考虑用 Maven 方式重新打包。

## 第五步：打开 Godot 项目

在 Godot 中导入这个项目文件：

- [project.godot](/Users/cuihaoyu/Desktop/集合/hello-agents/Helloagents-AI-Town/helloagents-ai-town/project.godot)

不要导入 `scenes/main.tscn`，正确做法是导入整个 Godot 项目目录：

- `Helloagents-AI-Town/helloagents-ai-town`

当前 `project.godot` 已经配置好了 AutoLoad：

- `Config = res://scripts/config.gd`
- `APIClient = res://scripts/api_client.gd`

## 第六步：运行游戏

在 Godot 编辑器中直接运行项目。

当前默认主场景已经配置在 `project.godot` 里，不需要手动改：

- `run/main_scene`

## 游戏操作

- `W/A/S/D` 移动
- `E` 靠近 NPC 后发起交互
- `Enter` 在对话框中发送消息
- `Esc` 关闭对话框

## 自检顺序

如果你启动后无法对话，建议按这个顺序检查：

1. `http://localhost:8000/health` 是否可访问
2. Java 网关 `8080` 是否已启动
3. Redis / RabbitMQ / MySQL 是否都已启动
4. `backend/mq_consumer.py` 是否正在运行
5. Godot 的 `scripts/config.gd` 是否仍然指向 `localhost:8080`

## 常见问题

### 1. Godot 能打开，但聊天没回复

优先检查：

- Java 网关是否启动
- `mq_consumer.py` 是否启动
- RabbitMQ / Redis 是否启动

这是当前项目里最常见的断链点。

### 2. FastAPI 已启动，但游戏还是不能聊天

因为当前前端不是直连 FastAPI。

真实链路是：

```text
Godot -> Java Gateway -> MQ / Redis -> Python
```

所以只开 `backend/main.py` 不够。

### 3. Godot 导入失败

请确认你导入的是整个目录：

- `helloagents-ai-town/`

而不是单独的 `.tscn` 场景文件。

### 4. 端口不一致

如果你改过端口，要同步修改这些地方：

- [helloagents-ai-town/scripts/config.gd](/Users/cuihaoyu/Desktop/集合/hello-agents/Helloagents-AI-Town/helloagents-ai-town/scripts/config.gd)
- [java-gateway/src/main/resources/application.yml](/Users/cuihaoyu/Desktop/集合/hello-agents/Helloagents-AI-Town/java-gateway/src/main/resources/application.yml)

## 最小启动清单

如果你只想尽快跑起来，至少要保证这 5 项都在：

1. MySQL
2. Redis
3. RabbitMQ
4. `python backend/main.py`
5. `python backend/mq_consumer.py`
6. 设置 `MYSQL_PASSWORD` 后，运行 `./mvnw spring-boot:run` 启动 Java 网关
7. Godot 运行 `helloagents-ai-town`

## 当前文档说明

这份指南已经从旧版“Godot 直连 FastAPI”改成当前仓库实际使用的启动方式。如果你后面把 `java-gateway` 源码补齐了，启动部分还可以再更新一版。
