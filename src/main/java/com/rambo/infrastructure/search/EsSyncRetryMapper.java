package com.rambo.infrastructure.search;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EsSyncRetryMapper extends BaseMapper<EsSyncRetry> {
}
