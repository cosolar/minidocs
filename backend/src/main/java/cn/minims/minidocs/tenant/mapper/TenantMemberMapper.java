package cn.minims.minidocs.tenant.mapper;

import cn.minims.minidocs.tenant.entity.TenantMember;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TenantMemberMapper extends BaseMapper<TenantMember> {
}
