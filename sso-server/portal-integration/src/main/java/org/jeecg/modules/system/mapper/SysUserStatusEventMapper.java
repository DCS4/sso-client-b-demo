package org.jeecg.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.jeecg.modules.system.entity.SysUserStatusEvent;

/** 用户状态事件 Outbox Mapper。 */
@Mapper
public interface SysUserStatusEventMapper extends BaseMapper<SysUserStatusEvent> {
}
