package cn.minims.minidocs.doc.mapper;

import cn.minims.minidocs.doc.entity.DocEditLock;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocEditLockMapper extends BaseMapper<DocEditLock> {
}
