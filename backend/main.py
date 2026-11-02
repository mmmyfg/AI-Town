"""
赛博小镇 - AI 推理微服务主程序 (FastAPI)
"""

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import uvicorn
from langchain_agent import LangChainNPCAgent

app = FastAPI(title="赛博小镇 AI 推理微服务", version="2.0.0")

print("🧠 正在初始化 LangChain AI 大脑...")
agent = LangChainNPCAgent()

# ==========================================
# 数据交互模型
# ==========================================
class ReactRequest(BaseModel):
    player_id: str     # 玩家ID，用于记忆隔离
    npc_id: str        # NPC ID，用于记忆隔离
    personality: str  
    observation: str  
    memory: str = ""  
    conversation_id: str = ""
    current_affinity: int = 50

class BatchReactRequest(BaseModel):
    context: str   
    npc_list: str  

# ==========================================
# 核心 API 路由
# ==========================================
@app.post("/agent/react")
async def generate_reaction(request: ReactRequest):
    """单人对话接口"""
    try:
        reply = agent.react(
            player_id=request.player_id,
            npc_id=request.npc_id,
            personality=request.personality,
            observation=request.observation,
            memory=request.memory,
            conversation_id=request.conversation_id,
            current_affinity=request.current_affinity,
        )
        # 注意：Java 传过来的 reply 我们原样包在 json 里返回
        return {"success": True, "reply": reply}
    except Exception as e:
        print(f"❌ 单人接口异常: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/agent/batch_react")
async def generate_batch_reaction(request: BatchReactRequest):
    """批量状态刷新接口"""
    try:
        reply_json_str = agent.batch_react(
            context=request.context,
            npc_list=request.npc_list
        )
        return {"success": True, "reply": reply_json_str}
    except Exception as e:
        print(f"❌ 批量接口异常: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/health")
async def health_check():
    """健康检查接口"""
    return {"status": "healthy", "service": "LLM_Reasoning_Node"}

if __name__ == "__main__":
    print("\n🚀 AI 推理微服务已启动!")
    print("📍 监听接口: POST http://127.0.0.1:8000/agent/react")
    uvicorn.run("main:app", host="127.0.0.1", port=8000, reload=False)
