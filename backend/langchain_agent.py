import json
import math
import os
import re
import threading
import uuid
from contextvars import ContextVar
from datetime import datetime, timezone
from dataclasses import asdict, dataclass, field
from typing import Any

import redis
from dotenv import load_dotenv
from langchain_community.vectorstores import Chroma
from langchain_core.messages import AIMessage, HumanMessage
from langchain_core.output_parsers import StrOutputParser
from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder
from langchain_huggingface import HuggingFaceEmbeddings
from langchain_openai import ChatOpenAI

load_dotenv(override=True)

# Redis 不可用时仅用于本次进程降级，主路径的短期记忆由 Redis 持久化。
memory_store: dict[str, list[Any]] = {}

# 进程内关系状态缓存：第一阶段先放在 Python 内存里，后续可接 Java / MySQL。
# affinity_store 保存数值好感度，affinity_reason_store 保存最近几次变化原因。
affinity_store: dict[str, int] = {}
affinity_reason_store: dict[str, list[str]] = {}
_scene_affinity: ContextVar[tuple[str, str, int] | None] = ContextVar("scene_affinity", default=None)


def current_affinity_for(player_id: str, npc_id: str) -> int:
    scene = _scene_affinity.get()
    if scene is not None and scene[:2] == (player_id, npc_id):
        return scene[2]
    return affinity_store.get(get_session_id(player_id, npc_id), 50)

# Java / MySQL 现在是 NPC 画像的唯一真实来源。
# Python 侧不再维护按角色写死的人物资料，只保留最小兜底值，
# 防止上游字段缺失时工具直接报错。
DEFAULT_NPC_PROFILE = {
    "title": "小镇居民",
    "personality": "谨慎、自然，愿意与熟悉的人交流。",
    "background": "长期生活在赛博小镇，对周围环境十分熟悉。",
}

# 语义路由的兜底关键词。
# 主路径会优先使用 embedding 相似度，关键词只作为低成本 fallback。
ROUTE_KEYWORDS = {
    "memory": ["记住", "记得", "还记得", "上次", "之前", "以前", "聊过", "送过"],
    "affinity": ["关系", "好感", "喜欢我", "讨厌我", "生气", "相处", "怎么提升", "反感"],
    "profile": ["你是谁", "你叫什么", "介绍", "你喜欢什么", "你平时", "你在做什么", "心情", "状态"],
    "weather": ["天气", "下雨", "晴", "气温", "出门", "出行", "适合聊天", "适合见面"],
    "advice": ["建议", "怎么", "应该", "合适吗", "能不能", "如何"],
}

# 每个 intent 准备少量原型句，启动时会编码成向量。
# 运行时把玩家输入和这些原型句做 cosine similarity，超过阈值就触发对应规划路径。
ROUTE_INTENT_EXAMPLES = {
    "memory": [
        "你还记得我吗",
        "我们上次聊了什么",
        "你记不记得我之前说过的话",
        "你还记得我送过你的东西吗",
        "刚才发生了什么你还记得吗",
    ],
    "affinity": [
        "我们现在关系怎么样",
        "你喜欢我吗",
        "你是不是讨厌我",
        "我们的好感度是多少",
        "我该怎么改善我们之间的关系",
    ],
    "profile": [
        "你是谁",
        "你叫什么名字",
        "介绍一下你自己",
        "你现在心情怎么样",
        "你现在是什么状态",
        "你平时喜欢做什么",
    ],
    "weather": [
        "今天天气怎么样",
        "明天会下雨吗",
        "气温适合出门吗",
        "南京天气如何",
        "这个天气适合见面吗",
    ],
    "advice": [
        "你有什么建议",
        "我应该怎么做",
        "你觉得这样合适吗",
        "我该如何处理这件事",
        "能不能给我一点意见",
    ],
}

ROUTE_DIRECT_EXAMPLES = [
    "随便聊聊吧",
    "你好",
    "早上好",
    "hello",
    "你在吗",
    "嗯嗯",
    "哈哈",
    "没什么",
    "我只是来看看你",
]

EMBEDDING_MODEL_NAME = os.getenv(
    "EMBEDDING_MODEL_NAME",
    "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
)
ROUTE_SIMILARITY_THRESHOLD = 0.45
ROUTE_SIMILARITY_MARGIN = 0.06
MEMORY_SIMILARITY_THRESHOLD = float(os.getenv("MEMORY_SIMILARITY_THRESHOLD", "0.35"))
MEMORY_DEDUP_THRESHOLD = float(os.getenv("MEMORY_DEDUP_THRESHOLD", "0.92"))
MEMORY_HALF_LIFE_DAYS = float(os.getenv("MEMORY_HALF_LIFE_DAYS", "30"))
MEMORY_EXTRACTION_CONFIDENCE_THRESHOLD = float(
    os.getenv("MEMORY_EXTRACTION_CONFIDENCE_THRESHOLD", "0.65")
)
MEMORY_MAX_ATOMS_PER_TURN = int(os.getenv("MEMORY_MAX_ATOMS_PER_TURN", "4"))
SHORT_MEMORY_LIMIT = int(os.getenv("SHORT_MEMORY_LIMIT", "10"))
SHORT_MEMORY_TTL_SECONDS = int(os.getenv("SHORT_MEMORY_TTL_SECONDS", "604800"))
MEMORY_COLLECTION_NAME = os.getenv("MEMORY_COLLECTION_NAME", "cyber_town_memories_v2")

MEMORY_TYPES = {
    "preference",
    "profile",
    "event",
    "relationship",
    "promise",
    "plan",
    "interaction",
}

MEMORY_EXTRACTION_PROMPT = ChatPromptTemplate.from_messages(
    [
        (
            "system",
            """你是 NPC 的长期记忆提取器。请从本轮对话中提取值得未来回忆的独立事实。

要求：
1. 每条记忆只表达一个事实或事件，必须脱离原对话也能理解。
2. 只提取原对话明确支持的内容，禁止推测或补充不存在的信息。
3. 优先保留玩家偏好、个人资料、共同事件、关系变化、承诺和未来计划。
4. 忽略寒暄、客套话、临时措辞以及 NPC 回复中的虚构细节。
5. 最多返回 {max_memories} 条，content 控制在 100 字以内。
6. type 只能是 preference、profile、event、relationship、promise、plan 之一。
7. confidence 是 0 到 1 之间的数字，表示该事实被原对话明确支持的程度。
8. 如果没有值得长期保存的事实，返回空数组。

只返回合法 JSON，不要包含 Markdown。格式：
{{
  "memories": [
    {{"type": "preference", "content": "玩家最喜欢绿色", "confidence": 0.98}}
  ]
}}""",
        ),
        (
            "user",
            """玩家说：{player_message}

NPC 回应：{reply_text}

本轮好感度变化：{affinity_change}""",
        ),
    ]
)


@dataclass
class DialoguePlanStep:
    # step_type 目前主要有 tool / respond 两类。
    # tool 表示调用某个工具，respond 表示进入最终回复生成阶段。
    step_id: int
    step_type: str
    tool: str | None
    args: dict[str, Any] = field(default_factory=dict)
    reason: str = ""


@dataclass
class DialoguePlan:
    # route 记录这次消息被路由到 direct 还是 plan。
    # strategy 用于记录 Planner 的思路，方便后续日志展示和调试。
    need_planning: bool
    route: str
    goal: str
    strategy: str
    steps: list[DialoguePlanStep] = field(default_factory=list)


@dataclass
class ExecutionStepResult:
    # 保存单个工具步骤的执行结果，后面会整体喂给 Final Responder。
    step_id: int
    tool: str
    result: dict[str, Any]


@dataclass
class ExecutionTrace:
    # 一次 planning 执行的完整结果记录，便于日志打印和后续前端展示。
    goal: str
    route: str
    step_results: list[ExecutionStepResult] = field(default_factory=list)


def get_session_id(player_id: str, npc_id: str, conversation_id: str = "") -> str:
    return f"{player_id}:{npc_id}:{conversation_id or 'default'}"


class RedisShortTermMemory:
    def __init__(self):
        self.client: redis.Redis | None = None
        try:
            self.client = redis.Redis(
                host=os.getenv("REDIS_HOST", "localhost"),
                port=int(os.getenv("REDIS_PORT", "6379")),
                db=int(os.getenv("REDIS_DB", "0")),
                decode_responses=True,
                socket_connect_timeout=2,
                socket_timeout=2,
            )
            self.client.ping()
        except Exception as exc:
            print(f"⚠️ Redis 短期记忆不可用，降级为进程内记忆: {exc}")
            self.client = None

    def _key(self, session_id: str) -> str:
        return f"agent:short-memory:{session_id}"

    def load(self, session_id: str) -> list[Any]:
        if self.client is None:
            return list(memory_store.get(session_id, []))[-SHORT_MEMORY_LIMIT:]
        try:
            messages: list[Any] = []
            for raw in self.client.lrange(self._key(session_id), -SHORT_MEMORY_LIMIT, -1):
                item = json.loads(raw)
                content = str(item.get("content", ""))
                messages.append(AIMessage(content=content) if item.get("role") == "assistant"
                                else HumanMessage(content=content))
            return messages
        except Exception as exc:
            print(f"⚠️ 读取 Redis 短期记忆失败: {exc}")
            return list(memory_store.get(session_id, []))[-SHORT_MEMORY_LIMIT:]

    def append_turn(self, session_id: str, user_content: str, assistant_content: str) -> None:
        user = HumanMessage(content=user_content)
        assistant = AIMessage(content=assistant_content)
        fallback = memory_store.setdefault(session_id, [])
        fallback.extend([user, assistant])
        memory_store[session_id] = fallback[-SHORT_MEMORY_LIMIT:]
        if self.client is None:
            return
        try:
            pipe = self.client.pipeline(transaction=True)
            pipe.rpush(self._key(session_id), json.dumps({"role": "user", "content": user_content}, ensure_ascii=False))
            pipe.rpush(self._key(session_id), json.dumps({"role": "assistant", "content": assistant_content}, ensure_ascii=False))
            pipe.ltrim(self._key(session_id), -SHORT_MEMORY_LIMIT, -1)
            pipe.expire(self._key(session_id), SHORT_MEMORY_TTL_SECONDS)
            pipe.execute()
        except Exception as exc:
            print(f"⚠️ 写入 Redis 短期记忆失败: {exc}")


def get_affinity_level(score: int) -> str:
    # 用简单分段把数值好感度映射成可读关系等级，便于工具返回和提示词使用。
    if score >= 80:
        return "亲近"
    if score >= 60:
        return "信任"
    if score >= 40:
        return "普通"
    if score >= 20:
        return "疏离"
    return "戒备"


def clamp_affinity(score: int) -> int:
    # 好感度统一限制在 0~100，防止多轮对话后数值无限增长或变成负数。
    return max(0, min(100, score))


def extract_current_mood(observation: str) -> str:
    # Java 侧会把当前心情拼到 observation 里，这里直接从文本中解析。
    # 如果没有解析到，就默认 CALM。
    mood_match = re.search(r"心情.*?【([A-Z_]+)】", observation)
    if mood_match:
        return mood_match.group(1)
    return "CALM"


def summarize_text(text: str, limit: int = 40) -> str:
    # 把较长文本压缩成日志/关系原因里可读的一小段摘要。
    compact = re.sub(r"\s+", " ", text).strip()
    return compact[:limit] + ("..." if len(compact) > limit else "")


class LangChainNPCAgent:
    """
    赛博小镇的对话 Agent。

    第一阶段在现有单轮对话链路上增加：
    - Intent Router
    - Dialogue Planner
    - Tool Executor
    - Planned Responder
    """

    def __init__(self, model_name: str | None = None):
        provider = os.getenv("NPC_LLM_PROVIDER", "deepseek").strip().lower()
        self.provider = provider
        custom_settings = ("NPC_LLM_BASE_URL", "NPC_LLM_MODEL", "NPC_LLM_API_KEY")
        custom_values = [os.getenv(name, "").strip() for name in custom_settings]
        if provider == "deepseek":
            base_url = "https://api.deepseek.com"
            configured_model = os.getenv("NPC_LLM_MODEL", "deepseek-flash").strip() or "deepseek-flash"
            api_key = os.getenv("DEEPSEEK_API_KEY", "").strip()
            if not api_key:
                raise ValueError("未配置 DEEPSEEK_API_KEY，请写入 backend/.env 后重启消费者")
            extra_body = {"thinking": {"type": "disabled"}}
        elif provider == "groq":
            base_url = "https://api.groq.com/openai/v1"
            configured_model = "llama-3.1-8b-instant"
            api_key = os.getenv("GROQ_API_KEY", "").strip()
            if not api_key:
                raise ValueError("未配置 GROQ_API_KEY")
            extra_body = None
        elif provider == "custom":
            if not all(custom_values):
                raise ValueError("切换模型服务时必须同时配置 NPC_LLM_BASE_URL、NPC_LLM_MODEL 和 NPC_LLM_API_KEY")
            base_url, configured_model, api_key = custom_values
            extra_body = None
        else:
            raise ValueError(f"不支持的 NPC_LLM_PROVIDER: {provider}")

        self.llm = ChatOpenAI(
            model_name=model_name or configured_model,
            api_key=api_key,
            base_url=base_url,
            temperature=0.2 if provider == "deepseek" else 0.7,
            max_tokens=512 if provider == "deepseek" else 220,
            timeout=float(os.getenv("LLM_TIMEOUT_SECONDS", "45")),
            max_retries=0,
            **({"extra_body": extra_body} if extra_body is not None else {}),
        )
        # 长期记忆提取复用同一模型连接，但使用低温度减少事实漂移。
        self.memory_llm = self.llm.bind(
            temperature=0, max_tokens=320,
            **({"response_format": {"type": "json_object"}} if provider == "deepseek" else {}),
        )

        self.short_memory = RedisShortTermMemory()

        print("⏳ 正在初始化长期记忆库...")
        # 长期记忆使用本地向量库 Chroma：
        # - embeddings 负责把对话片段转成向量
        # - vector_db 负责持久化与相似检索
        local_only = os.getenv("EMBEDDING_LOCAL_FILES_ONLY", "false").lower() in {"1", "true", "yes"}
        self.embeddings = HuggingFaceEmbeddings(
            model_name=EMBEDDING_MODEL_NAME,
            model_kwargs={"local_files_only": True} if local_only else {},
        )
        self.vector_db = Chroma(
            collection_name=MEMORY_COLLECTION_NAME,
            embedding_function=self.embeddings,
            persist_directory="./chroma_db",
            collection_metadata={"hnsw:space": "cosine"},
        )
        self.intent_vectors = self.build_intent_vectors()
        self.direct_vectors = self.embeddings.embed_documents(ROUTE_DIRECT_EXAMPLES)
        print("✅ 长期记忆库加载完成！")

        # direct prompt：普通闲聊直接走这里。
        # 特点是链路短、成本低、速度快，不做额外工具规划。
        self.direct_prompt_template = ChatPromptTemplate.from_messages(
            [
                (
                    "system",
                    """你是赛博小镇的居民。请严格代入角色，不要出戏。

【你的身份设定】
{personality}

【属于你脑海深处的长期记忆】
{memory}

【核心任务：结合当前情绪进行人设演绎】
请仔细阅读玩家发送的当前环境观测（其中包含你当前的【实时心情状态】）。
1. 你的台词语调、神态描写必须完全契合你当前的心情状态（如【SLEEPING】时敷衍、【ANGRY】时暴躁）。
2. 回复简洁自然，尽量像真实说话，不要罗列分析过程。
3. 判断这句话对你好感度的影响：冒犯/打扰(-1到-3)，普通(0)，开心/送礼(1到3)。

⚠️ 最终回复必须且只能返回合法 JSON，不要包含 Markdown 标记。格式如下：
{{
  "reply": "你的第一人称台词(30-70字以内，可包含动作心理描写)",
  "affinity_change": 0
}}""",
                ),
                MessagesPlaceholder(variable_name="history"),
                ("user", "本轮观测：{observation}"),
            ]
        )

        # planned prompt：只有在 route_dialogue 判定需要 planning 时才走这里。
        # 它会拿到 Planner goal 和 Executor step_results，把工具结果自然融入 NPC 回复。
        self.planned_response_template = ChatPromptTemplate.from_messages(
            [
                (
                    "system",
                    """你是赛博小镇的居民，请严格代入角色。

【你的身份设定】
{personality}

【当前情绪】
{current_mood}

【长期记忆摘要】
{memory}

【规划目标】
{goal}

【工具执行结果】
{execution_trace}

【回复要求】
1. 不要暴露“我查了工具/我执行了步骤”等技术过程。
2. 要自然地把查询结果融入 NPC 台词里。
3. 如果工具结果显示关系较近，可以更亲切；如果关系一般，保持克制。
4. 判断这句话对好感度的影响，给出 -3 到 3 的整数。

⚠️ 最终回复必须且只能返回合法 JSON，不要包含 Markdown 标记。格式如下：
{{
  "reply": "符合人设的自然回复(40-90字以内)",
  "affinity_change": 0
}}""",
                ),
                MessagesPlaceholder(variable_name="history"),
                ("user", "本轮观测：{observation}"),
            ]
        )

        # 批量状态生成仍保留原来的独立链路，不和对话 planning 混在一起。
        self.batch_prompt_template = ChatPromptTemplate.from_messages(
            [
                (
                    "system",
                    """你是一个游戏环境旁白大脑。请根据当前的场景上下文，为指定的多个NPC生成一句符合其人设的简短状态台词（20-40字）。
必须严格按照以下 JSON 格式返回：{{"NPC名字1": "台词1", "NPC名字2": "台词2"}}""",
                ),
                ("user", "【当前场景上下文】：{context}\n\n【需要生成台词的NPC列表】：\n{npc_list}"),
            ]
        )
        self.batch_chain = self.batch_prompt_template | self.llm | StrOutputParser()

    # ==================== 工具层 ====================
    def get_current_weather(self, city: str) -> dict[str, Any]:
        # 结构化工具返回格式统一为 dict，便于 Executor 收集和最终日志输出。
        print(f"🌧️ [工具] get_current_weather(city={city})")
        weather_db = {
            "南京": "明天有阵雨，气温 18-24°C，空气湿度较高。",
            "伦敦": "明天阴天，偶尔有小雨，气温 12-16°C，风力较大。",
            "东京": "明天晴朗，气温 20-28°C，适合出行。",
        }
        return {
            "success": True,
            "tool": "get_current_weather",
            "city": city,
            "weather": weather_db.get(city, f"抱歉，气象站暂时没有 {city} 的数据。"),
        }

    def get_npc_profile(
        self,
        npc_id: str,
        personality: str = "",
        current_mood: str = "CALM",
        player_id: str = "",
    ) -> dict[str, Any]:
        # 当前实现默认信任 Java / MySQL 提供的真实人物资料。
        # Python 不再按 NPC 名字维护一份重复画像，只在上游字段缺失时使用通用兜底值。
        # 如果传了 player_id，还会顺带把该玩家与该 NPC 当前关系分数带回来。
        print(f"🪪 [工具] get_npc_profile(npc_id={npc_id})")
        affinity = current_affinity_for(player_id or "profile_lookup", npc_id)
        return {
            "success": True,
            "tool": "get_npc_profile",
            "npc_id": npc_id,
            "title": DEFAULT_NPC_PROFILE["title"],
            "personality": personality or DEFAULT_NPC_PROFILE["personality"],
            "background": DEFAULT_NPC_PROFILE["background"],
            "current_mood": current_mood,
            "affinity": affinity,
        }

    def get_recent_memory(
        self, player_id: str, npc_id: str, conversation_id: str = ""
    ) -> dict[str, Any]:
        # 永远按 player_id + npc_id 隔离；不再降级到 NPC 维度，避免跨玩家串记忆。
        print(f"🧠 [工具] get_recent_memory(player_id={player_id}, npc_id={npc_id})")
        memories = self.retrieve_long_term_memories(
            f"{player_id} 与 {npc_id} 的近期互动", player_id, npc_id, k=3
        )

        if not memories:
            recent_messages = self.short_memory.load(
                get_session_id(player_id, npc_id, conversation_id)
            )[-4:]
            for msg in recent_messages:
                memories.append(summarize_text(getattr(msg, "content", "")))

        return {
            "success": True,
            "tool": "get_recent_memory",
            "player_id": player_id,
            "npc_id": npc_id,
            "memories": memories[:3],
        }

    def memory_filter(self, player_id: str, npc_id: str) -> dict[str, Any]:
        return {
            "$and": [
                {"player_id": {"$eq": player_id}},
                {"npc_id": {"$eq": npc_id}},
            ]
        }

    def retrieve_long_term_memories(
        self, query: str, player_id: str, npc_id: str, k: int = 3
    ) -> list[str]:
        try:
            candidates = self.vector_db.similarity_search_with_relevance_scores(
                query,
                k=max(k * 2, 4),
                filter=self.memory_filter(player_id, npc_id),
            )
        except Exception as exc:
            print(f"⚠️ 长期记忆检索失败: {exc}")
            return []

        now = datetime.now(timezone.utc)
        ranked: list[tuple[float, str]] = []
        for doc, relevance in candidates:
            if relevance < MEMORY_SIMILARITY_THRESHOLD:
                continue
            created_raw = doc.metadata.get("created_at")
            age_days = 0.0
            if created_raw:
                try:
                    created = datetime.fromisoformat(str(created_raw).replace("Z", "+00:00"))
                    age_days = max(0.0, (now - created).total_seconds() / 86400)
                except (TypeError, ValueError):
                    age_days = 0.0
            recency = math.pow(0.5, age_days / max(MEMORY_HALF_LIFE_DAYS, 1.0))
            importance = float(doc.metadata.get("importance", 0.5))
            score = relevance * 0.75 + recency * 0.15 + importance * 0.10
            ranked.append((score, doc.page_content))
        ranked.sort(key=lambda item: item[0], reverse=True)
        return [content for _, content in ranked[:k]]

    def should_store_memory(self, player_message: str, affinity_change: int) -> tuple[bool, float]:
        normalized = re.sub(r"\s+", "", player_message)
        trivial = {"你好", "在吗", "嗯", "哦", "哈哈", "谢谢", "再见", "早上好", "晚上好"}
        if normalized in trivial or len(normalized) < 4:
            return False, 0.0
        flags = self.classify_message_by_keywords(player_message)
        importance = min(
            1.0,
            0.35
            + (0.30 if any(flags.values()) else 0.0)
            + min(abs(affinity_change) * 0.08, 0.25)
            + (0.10 if len(normalized) >= 20 else 0.0),
        )
        return importance >= 0.5, importance

    def fallback_memory_fragment(self, player_message: str, reply_text: str) -> dict[str, Any]:
        return {
            "memory_type": "interaction",
            "content": (
                f"玩家说：{summarize_text(player_message, 80)}；"
                f"我的回应：{summarize_text(reply_text, 100)}"
            ),
            "confidence": 1.0,
            "extraction_method": "fallback",
        }

    def parse_atomic_memories(self, response_text: str) -> list[dict[str, Any]]:
        parsed = self.parse_json_reply(response_text)
        raw_memories = parsed.get("memories")
        if not isinstance(raw_memories, list):
            raise ValueError("长期记忆提取结果缺少 memories 数组")

        memories: list[dict[str, Any]] = []
        seen_contents: set[str] = set()
        for raw in raw_memories[:MEMORY_MAX_ATOMS_PER_TURN]:
            if not isinstance(raw, dict):
                continue

            content = re.sub(r"\s+", " ", str(raw.get("content", ""))).strip()
            if not content:
                continue
            content = content[:200]

            try:
                confidence = float(raw.get("confidence", 0.0))
            except (TypeError, ValueError):
                continue
            confidence = max(0.0, min(confidence, 1.0))
            if confidence < MEMORY_EXTRACTION_CONFIDENCE_THRESHOLD:
                continue

            normalized = re.sub(r"\s+", "", content).lower()
            if normalized in seen_contents:
                continue
            seen_contents.add(normalized)

            memory_type = str(raw.get("type", "event")).strip().lower()
            if memory_type not in MEMORY_TYPES:
                memory_type = "event"
            memories.append({
                "memory_type": memory_type,
                "content": content,
                "confidence": confidence,
                "extraction_method": "llm",
            })
        return memories

    def extract_atomic_memories(
        self,
        player_message: str,
        reply_text: str,
        affinity_change: int,
    ) -> list[dict[str, Any]]:
        try:
            messages = MEMORY_EXTRACTION_PROMPT.format_messages(
                max_memories=MEMORY_MAX_ATOMS_PER_TURN,
                player_message=player_message,
                reply_text=reply_text,
                affinity_change=affinity_change,
            )
            response = self.memory_llm.invoke(messages)
            return self.parse_atomic_memories(str(response.content))
        except Exception as exc:
            print(f"⚠️ 长期记忆原子化提取失败，降级为文本片段: {exc}")
            return [self.fallback_memory_fragment(player_message, reply_text)]

    def store_long_term_memory(
        self,
        player_id: str,
        npc_id: str,
        conversation_id: str,
        player_message: str,
        reply_text: str,
        affinity_change: int,
        source_id: str | None = None,
    ) -> None:
        if source_id:
            existing = self.vector_db.get(where={"source_id": {"$eq": source_id}}, limit=1)
            if existing.get("ids"):
                return
        should_store, importance = self.should_store_memory(player_message, affinity_change)
        if not should_store:
            return

        atomic_memories = self.extract_atomic_memories(
            player_message=player_message,
            reply_text=reply_text,
            affinity_change=affinity_change,
        )
        if not atomic_memories:
            print("🧠 [长期记忆] 本轮没有可持久化的原子记忆")
            return

        texts: list[str] = []
        metadatas: list[dict[str, Any]] = []
        ids: list[str] = []
        created_at = datetime.now(timezone.utc).isoformat()
        for memory in atomic_memories:
            memory_fragment = str(memory["content"])
            try:
                duplicate = self.vector_db.similarity_search_with_relevance_scores(
                    memory_fragment,
                    k=1,
                    filter=self.memory_filter(player_id, npc_id),
                )
                if duplicate and duplicate[0][1] >= MEMORY_DEDUP_THRESHOLD:
                    print(f"🧠 [长期记忆] 跳过近似重复记忆: {memory_fragment}")
                    continue
            except Exception as exc:
                print(f"⚠️ 长期记忆去重查询失败，继续写入: {exc}")

            texts.append(memory_fragment)
            if source_id:
                ids.append(str(uuid.uuid5(uuid.NAMESPACE_URL, f"{source_id}:{len(ids)}")))
            metadatas.append({
                "player_id": player_id,
                "npc_id": npc_id,
                "conversation_id": conversation_id or "default",
                "importance": importance,
                "memory_type": str(memory["memory_type"]),
                "confidence": float(memory["confidence"]),
                "extraction_method": str(memory["extraction_method"]),
                "created_at": created_at,
                **({"source_id": source_id} if source_id else {}),
            })

        if texts:
            self.vector_db.add_texts(texts=texts, metadatas=metadatas,
                                     **({"ids": ids} if source_id else {}))

    def get_affinity_status(self, player_id: str, npc_id: str) -> dict[str, Any]:
        # 第一阶段好感度数据还在 Python 进程内维护。
        # 后续如果 Java 能提供真实关系数据，这里可以替换成远程查询。
        print(f"💞 [工具] get_affinity_status(player_id={player_id}, npc_id={npc_id})")
        session_id = get_session_id(player_id, npc_id)
        current_affinity = current_affinity_for(player_id, npc_id)
        reasons = affinity_reason_store.get(session_id, [])
        recent_reason = reasons[-1] if reasons else "最近互动整体平稳，没有明显冲突。"
        return {
            "success": True,
            "tool": "get_affinity_status",
            "player_id": player_id,
            "npc_id": npc_id,
            "current_affinity": current_affinity,
            "affinity_level": get_affinity_level(current_affinity),
            "recent_reason": recent_reason,
        }

    # ==================== Router / Planner / Executor ====================
    def build_intent_vectors(self) -> dict[str, list[list[float]]]:
        # 将每个意图的原型句提前编码，避免每轮对话重复计算静态样本。
        intent_vectors: dict[str, list[list[float]]] = {}
        for intent, examples in ROUTE_INTENT_EXAMPLES.items():
            intent_vectors[intent] = self.embeddings.embed_documents(examples)
        return intent_vectors

    def extract_player_message(self, observation: str) -> str:
        # Java 会把状态、心情和玩家消息拼在 observation 里。
        # 路由只需要玩家真实输入，否则“心情/状态”等系统字段会误触发 profile。
        markers = ("玩家同时对你说：", "玩家对你说：")
        for marker in markers:
            if marker in observation:
                return observation.rsplit(marker, 1)[-1].strip()
        return observation.strip()

    def cosine_similarity(self, left: list[float], right: list[float]) -> float:
        dot = sum(a * b for a, b in zip(left, right))
        left_norm = math.sqrt(sum(a * a for a in left))
        right_norm = math.sqrt(sum(b * b for b in right))
        if left_norm == 0 or right_norm == 0:
            return 0.0
        return dot / (left_norm * right_norm)

    def classify_message_by_embedding(self, message: str) -> tuple[dict[str, bool], dict[str, float]]:
        query_vector = self.embeddings.embed_query(message)
        scores: dict[str, float] = {}
        flags: dict[str, bool] = {}

        for intent, vectors in self.intent_vectors.items():
            best_score = max(self.cosine_similarity(query_vector, vector) for vector in vectors)
            scores[intent] = round(best_score, 4)

        direct_score = max(self.cosine_similarity(query_vector, vector) for vector in self.direct_vectors)
        scores["direct"] = round(direct_score, 4)

        if not scores:
            return flags, scores

        best_intent = max(scores, key=scores.get)
        if best_intent == "direct":
            return flags, scores

        best_score = scores[best_intent]
        sorted_scores = sorted(scores.values(), reverse=True)
        runner_up = sorted_scores[1] if len(sorted_scores) > 1 else 0.0
        if best_score >= ROUTE_SIMILARITY_THRESHOLD and best_score - runner_up >= ROUTE_SIMILARITY_MARGIN:
            flags[best_intent] = True

        return flags, scores

    def classify_message_by_keywords(self, message: str) -> dict[str, bool]:
        lowered = message.lower()
        return {
            category: any(keyword in message or keyword in lowered for keyword in keywords)
            for category, keywords in ROUTE_KEYWORDS.items()
        }

    def classify_message(self, observation: str) -> dict[str, bool]:
        # 主路径：embedding 语义相似度路由。
        # 兜底：关键词规则，避免短句、专有词或 embedding 边界样本漏判。
        message = self.extract_player_message(observation)
        embedding_flags, scores = self.classify_message_by_embedding(message)
        keyword_flags = self.classify_message_by_keywords(message)
        flags = {
            intent: embedding_flags.get(intent, False) or keyword_flags.get(intent, False)
            for intent in ROUTE_INTENT_EXAMPLES
        }
        active = [intent for intent, matched in flags.items() if matched]
        print(
            f"🧭 [EmbeddingRouter] message={message!r} scores={scores} "
            f"keyword_flags={keyword_flags} active={active}"
        )
        return flags

    def route_dialogue(self, observation: str) -> str:
        # 当前只有两条主路由：
        # - direct：普通闲聊
        # - plan：需要走工具查询 / 多步思考
        flags = self.classify_message(observation)
        if any(flags.values()):
            if flags["memory"] or flags["affinity"] or flags["weather"] or flags["advice"] or flags["profile"]:
                return "plan"
        return "direct"

    def plan_dialogue(
        self,
        player_id: str,
        npc_id: str,
        conversation_id: str,
        personality: str,
        observation: str,
        current_mood: str,
    ) -> DialoguePlan:
        # Planner 的职责不是直接生成台词，而是决定：
        # - 这次回复的目标是什么
        # - 需要调用哪些工具
        # - 调用顺序是什么
        flags = self.classify_message(observation)
        steps: list[DialoguePlanStep] = []
        goal = "用符合人设的方式回应玩家"
        strategy = "直接结合当前情绪与记忆做自然回复"
        step_id = 1

        # 天气相关问题：先查天气，再补角色画像，让后续回复更自然。
        if flags["weather"]:
            goal = "结合外部天气信息回答玩家的问题"
            strategy = "先查天气，再读取当前角色状态，最后自然组织回复"
            city = self.extract_city(observation)
            steps.append(
                DialoguePlanStep(
                    step_id=step_id,
                    step_type="tool",
                    tool="get_current_weather",
                    args={"city": city},
                    reason="需要先获得天气事实信息",
                )
            )
            step_id += 1
            steps.append(
                DialoguePlanStep(
                    step_id=step_id,
                    step_type="tool",
                    tool="get_npc_profile",
                    args={
                        "npc_id": npc_id,
                        "personality": personality,
                        "current_mood": current_mood,
                        "player_id": player_id,
                    },
                    reason="让回复保持人设并结合当前心情",
                )
            )
            step_id += 1

        # 身份/状态类问题：至少要补一份角色画像。
        if flags["profile"] and not any(step.tool == "get_npc_profile" for step in steps):
            goal = "回答玩家关于 NPC 身份或状态的问题"
            strategy = "先读取角色画像，再结合心情自然回复"
            steps.append(
                DialoguePlanStep(
                    step_id=step_id,
                    step_type="tool",
                    tool="get_npc_profile",
                    args={
                        "npc_id": npc_id,
                        "personality": personality,
                        "current_mood": current_mood,
                        "player_id": player_id,
                    },
                    reason="补充角色身份、性格和当前状态信息",
                )
            )
            step_id += 1

        # 关系/建议类问题：先查关系状态，必要时再结合近期记忆解释原因。
        if flags["affinity"]:
            goal = "回答玩家关于关系状态或相处建议的问题"
            strategy = "先读取关系状态，再结合近期互动组织回复"
            steps.append(
                DialoguePlanStep(
                    step_id=step_id,
                    step_type="tool",
                    tool="get_affinity_status",
                    args={"player_id": player_id, "npc_id": npc_id},
                    reason="先确认当前关系强弱和最近变化原因",
                )
            )
            step_id += 1
            if "原因" in observation or "为什么" in observation or flags["advice"]:
                steps.append(
                    DialoguePlanStep(
                        step_id=step_id,
                        step_type="tool",
                        tool="get_recent_memory",
                        args={
                            "player_id": player_id,
                            "npc_id": npc_id,
                            "conversation_id": conversation_id,
                        },
                        reason="结合近期互动解释关系变化来源",
                    )
                )
                step_id += 1

        # 回忆类问题：直接走近期记忆检索。
        if flags["memory"]:
            goal = "回顾玩家与 NPC 的近期互动并自然回应"
            strategy = "先检索近期记忆，再提炼成角色视角的回忆"
            steps.append(
                DialoguePlanStep(
                    step_id=step_id,
                    step_type="tool",
                    tool="get_recent_memory",
                    args={
                        "player_id": player_id,
                        "npc_id": npc_id,
                        "conversation_id": conversation_id,
                    },
                    reason="需要从记忆库里找出相关互动",
                )
            )
            step_id += 1

        # 如果没有匹配到任何 planning 场景，就降级为 direct。
        if not steps:
            return DialoguePlan(
                need_planning=False,
                route="direct",
                goal="普通聊天",
                strategy="直接回复",
                steps=[],
            )

        # 所有工具步骤之后，统一进入最终回复生成阶段。
        steps.append(
            DialoguePlanStep(
                step_id=step_id,
                step_type="respond",
                tool=None,
                args={},
                reason="结合工具结果生成最终回复",
            )
        )

        return DialoguePlan(
            need_planning=True,
            route="plan",
            goal=goal,
            strategy=strategy,
            steps=steps,
        )

    def execute_plan(self, plan: DialoguePlan) -> ExecutionTrace:
        # Executor 只做一件事：按 plan 顺序执行工具并收集结构化结果。
        trace = ExecutionTrace(goal=plan.goal, route=plan.route)
        tool_map = {
            "get_current_weather": self.get_current_weather,
            "get_npc_profile": self.get_npc_profile,
            "get_recent_memory": self.get_recent_memory,
            "get_affinity_status": self.get_affinity_status,
        }

        print("\n🧭 [Planner] 生成对话计划:")
        print(json.dumps(self.plan_to_dict(plan), ensure_ascii=False, indent=2))

        for step in plan.steps:
            if step.step_type != "tool" or not step.tool:
                continue
            tool_fn = tool_map.get(step.tool)
            if tool_fn is None:
                result = {"success": False, "error": f"未知工具: {step.tool}"}
            else:
                result = tool_fn(**step.args)
            trace.step_results.append(
                ExecutionStepResult(step_id=step.step_id, tool=step.tool, result=result)
            )

        print("🛠️ [Executor] 执行结果:")
        print(json.dumps(self.trace_to_dict(trace), ensure_ascii=False, indent=2))
        return trace

    # ==================== 回复生成 ====================
    def generate_direct_reply(
        self,
        personality: str,
        memory: str,
        observation: str,
        history_messages: list[Any],
    ) -> str:
        # direct 回复：只把角色设定、长期记忆、短期历史和当前输入交给模型。
        messages = self.direct_prompt_template.format_messages(
            personality=personality,
            memory=memory,
            observation=observation,
            history=self.history_for_model(history_messages),
        )
        ai_msg = self.response_model().invoke(messages)
        return ai_msg.content

    def generate_planned_reply(
        self,
        personality: str,
        memory: str,
        observation: str,
        current_mood: str,
        history_messages: list[Any],
        plan: DialoguePlan,
        trace: ExecutionTrace,
    ) -> str:
        # planned 回复：把 Planner goal + Executor trace 也一起注入。
        # 这样模型能“像是思考过之后”再给出更有依据的台词。
        messages = self.planned_response_template.format_messages(
            personality=personality,
            memory=memory,
            observation=observation,
            current_mood=current_mood,
            goal=plan.goal,
            execution_trace=json.dumps(self.trace_to_dict(trace), ensure_ascii=False, indent=2),
            history=self.history_for_model(history_messages),
        )
        ai_msg = self.response_model().invoke(messages)
        return ai_msg.content

    def response_model(self):
        if getattr(self, "provider", "") == "deepseek":
            return self.llm.bind(response_format={"type": "json_object"})
        return self.llm

    def history_for_model(self, history_messages: list[Any]) -> list[Any]:
        if getattr(self, "provider", "") != "deepseek":
            return history_messages
        # 记忆中保留自然语言台词；只在模型调用时把历史 NPC 回合包装成
        # 与本轮相同的 JSON 形式，避免模型照抄过去的纯文本输出格式。
        return [AIMessage(content=json.dumps({"reply": str(message.content),
                                              "affinity_change": 0}, ensure_ascii=False))
                if isinstance(message, AIMessage) else message for message in history_messages]

    # ==================== 主流程 ====================
    def react(
        self,
        player_id: str,
        npc_id: str,
        personality: str,
        observation: str,
        memory: str = "",
        conversation_id: str = "",
        current_affinity: int = 50,
        cancel_event: threading.Event | None = None,
        persist_memory: bool = True,
        persist_long_term: bool = True,
    ) -> str:
        session_id = get_session_id(player_id, npc_id, conversation_id)
        history_messages = self.short_memory.load(session_id)
        if persist_memory:
            affinity_store[get_session_id(player_id, npc_id)] = clamp_affinity(current_affinity)
        else:
            _scene_affinity.set((player_id, npc_id, clamp_affinity(current_affinity)))
        current_mood = extract_current_mood(observation)
        player_message = self.extract_player_message(observation)

        recalled = self.retrieve_long_term_memories(
            player_message, player_id, npc_id, k=2
        )
        long_term_memory = memory
        if recalled:
            long_term_memory += "\n你隐约回想起之前的一些事：\n" + "\n".join(recalled)

        route = self.route_dialogue(observation)
        print(f"\n🧭 [Router] route={route} npc={npc_id}")

        if route == "direct":
            response_text = self.generate_direct_reply(
                personality=personality,
                memory=long_term_memory,
                observation=observation,
                history_messages=history_messages,
            )
        else:
            plan = self.plan_dialogue(
                player_id=player_id,
                npc_id=npc_id,
                conversation_id=conversation_id,
                personality=personality,
                observation=observation,
                current_mood=current_mood,
            )
            if not plan.need_planning:
                response_text = self.generate_direct_reply(
                    personality=personality,
                    memory=long_term_memory,
                    observation=observation,
                    history_messages=history_messages,
                )
            else:
                trace = self.execute_plan(plan)
                response_text = self.generate_planned_reply(
                    personality=personality,
                    memory=long_term_memory,
                    observation=observation,
                    current_mood=current_mood,
                    history_messages=history_messages,
                    plan=plan,
                    trace=trace,
                )

        parsed_response = self.parse_json_reply(response_text)
        reply_text = parsed_response.get("reply")
        if not isinstance(reply_text, str) or not reply_text.strip():
            raise ValueError("模型结构化响应缺少 reply")
        reply_text = reply_text.strip()
        raw_delta = parsed_response.get("affinity_change", 0)
        affinity_change = raw_delta if isinstance(raw_delta, int) and not isinstance(raw_delta, bool) \
            and -3 <= raw_delta <= 3 else 0
        parsed_response = {"reply": reply_text, "affinity_change": affinity_change}

        if cancel_event is not None and cancel_event.is_set():
            raise TimeoutError("Scene character result arrived after timeout")

        if persist_memory:
            self.short_memory.append_turn(session_id, player_message, reply_text)
            if persist_long_term:
                self.store_long_term_memory(
                    player_id=player_id,
                    npc_id=npc_id,
                    conversation_id=conversation_id,
                    player_message=player_message,
                    reply_text=reply_text,
                    affinity_change=affinity_change,
                )
            self.update_affinity_state(
                player_id=player_id,
                npc_id=npc_id,
                affinity_change=affinity_change,
                observation=player_message,
            )
        # 向 MQ 层返回归一化后的对象 JSON，避免模型偶发双重编码导致下游再次解析失败。
        return json.dumps(parsed_response, ensure_ascii=False)

    def batch_react(self, context: str, npc_list: str) -> str:
        # 批量状态生成与对话 planning 解耦，避免互相影响。
        try:
            return self.batch_chain.invoke({"context": context, "npc_list": npc_list})
        except Exception as e:
            print(f"❌ 批量调用失败: {e}")
            return "{}"

    # ==================== 工具函数 ====================
    def extract_city(self, observation: str) -> str:
        # 第一阶段只做非常轻的城市提取，识别不到就给默认值。
        for city in ("南京", "伦敦", "东京"):
            if city in observation:
                return city
        return "南京"

    def parse_json_reply(self, response_text: str) -> dict[str, Any]:
        # 对 Markdown 包装和少数模型产生的双重 JSON 编码做兼容处理，
        # 但最终结果必须是对象，避免字符串/数组流入业务状态更新。
        clean_response = response_text.strip()
        if clean_response.startswith("```json"):
            clean_response = clean_response.replace("```json", "").replace("```", "").strip()
        if not clean_response:
            raise ValueError("模型返回空响应")
        try:
            parsed: Any = json.loads(clean_response)
        except json.JSONDecodeError as exc:
            if clean_response[0] not in "{[`\"" and not clean_response.startswith("<think"):
                return {"reply": clean_response[:1000], "affinity_change": 0}
            raise ValueError("模型返回无效 JSON") from exc
        if isinstance(parsed, str):
            try:
                parsed = json.loads(parsed)
            except json.JSONDecodeError:
                return {"reply": parsed[:1000], "affinity_change": 0}
        if not isinstance(parsed, dict):
            raise ValueError("模型结构化响应必须是 JSON 对象")
        return parsed

    def update_affinity_state(self, player_id: str, npc_id: str, affinity_change: int, observation: str) -> None:
        # 把本轮回复造成的好感度变化写回进程内状态，并记录变化原因。
        session_id = get_session_id(player_id, npc_id)
        current_score = affinity_store.get(session_id, 50)
        affinity_store[session_id] = clamp_affinity(current_score + affinity_change)

        reasons = affinity_reason_store.setdefault(session_id, [])
        if affinity_change > 0:
            reasons.append(f"玩家最近的互动带来积极感受：{summarize_text(observation)}")
        elif affinity_change < 0:
            reasons.append(f"玩家最近的互动让关系变差：{summarize_text(observation)}")
        else:
            reasons.append(f"最近一次互动整体平稳：{summarize_text(observation)}")
        affinity_reason_store[session_id] = reasons[-5:]

    def plan_to_dict(self, plan: DialoguePlan) -> dict[str, Any]:
        # 统一把 dataclass 转成可打印 JSON，方便日志和后续调试。
        return {
            "need_planning": plan.need_planning,
            "route": plan.route,
            "goal": plan.goal,
            "strategy": plan.strategy,
            "steps": [asdict(step) for step in plan.steps],
        }

    def trace_to_dict(self, trace: ExecutionTrace) -> dict[str, Any]:
        # 统一把执行轨迹转成可打印 JSON。
        return {
            "goal": trace.goal,
            "route": trace.route,
            "step_results": [asdict(step) for step in trace.step_results],
        }


if __name__ == "__main__":
    print("🚀 正在启动对话版 Planner-Executor Agent 测试...")
    agent = LangChainNPCAgent()

    agent.react(
        player_id="player_1",
        npc_id="雪之下雪乃",
        personality="你是雪之下雪乃，性格高冷，毒舌，但能给出克制而准确的建议。",
        observation="你现在的状态是：在小镇广场。你当前的心情是：【CALM】。玩家对你说：你还记得我上次和你聊了什么吗？",
    )

    print("\n✅ 测试执行完毕！")
