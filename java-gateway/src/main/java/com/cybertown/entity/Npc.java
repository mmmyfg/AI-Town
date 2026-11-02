package com.cybertown.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;

@TableName("npcs")
public class Npc {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String npcId;         // 对应数据库的 npc_id

    private String title;         // 对应数据库的 title

    private String personality;   // 对应数据库的 personality

    private String background;    // 对应数据库的 background

    private String initialStatus; // 对应数据库的 initial_status

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Date createdAt;       // 对应数据库里的 created_at

    private Integer affinity;     // 对应数据库里的 affinity (玩家好感度)

    private String currentMood;   // ✨ 新增：对应数据库里的 current_mood (当前心情状态)

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getNpcId() {
        return npcId;
    }

    public void setNpcId(String npcId) {
        this.npcId = npcId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getPersonality() {
        return personality;
    }

    public void setPersonality(String personality) {
        this.personality = personality;
    }

    public String getBackground() {
        return background;
    }

    public void setBackground(String background) {
        this.background = background;
    }

    public String getInitialStatus() {
        return initialStatus;
    }

    public void setInitialStatus(String initialStatus) {
        this.initialStatus = initialStatus;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Integer getAffinity() {
        return affinity;
    }

    public void setAffinity(Integer affinity) {
        this.affinity = affinity;
    }

    public String getCurrentMood() {
        return currentMood;
    }

    public void setCurrentMood(String currentMood) {
        this.currentMood = currentMood;
    }
}
