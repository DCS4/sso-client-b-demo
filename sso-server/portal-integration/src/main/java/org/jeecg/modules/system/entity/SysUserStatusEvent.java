package org.jeecg.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 用户身份状态变化的可靠投递记录。
 *
 * <p>该记录与用户禁用/删除操作位于同一数据库事务；投递成功前不会被删除。</p>
 */
@Data
@TableName("user_status_event")
public class SysUserStatusEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    /** USER_DISABLED / USER_DELETED */
    private String eventType;
    /** SSO subject，当前等于 sys_user.id */
    private String subject;
    /** 0=待投递，1=成功，2=待重试 */
    private Integer status;
    private Integer retryCount;
    private Date nextRetryAt;
    private String claimToken;
    private Date claimUntil;
    private String lastError;
    private Date createTime;
    private Date updateTime;
}
