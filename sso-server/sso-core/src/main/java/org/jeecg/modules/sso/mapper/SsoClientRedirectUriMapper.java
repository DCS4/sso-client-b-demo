package org.jeecg.modules.sso.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jeecg.modules.sso.entity.SsoClientRedirectUri;

@Mapper
public interface SsoClientRedirectUriMapper extends BaseMapper<SsoClientRedirectUri> {
    @Select("SELECT redirect_uri FROM sso_client_redirect_uri WHERE client_id = #{clientId}")
    List<String> selectUrisByClientId(@Param("clientId") String clientId);
}
