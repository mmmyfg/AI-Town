package com.cybertown.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cybertown.entity.Npc;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NpcMapper extends BaseMapper<Npc> {
    // 继承 BaseMapper 后，MyBatis-Plus 会自动帮你写好增删改查
}