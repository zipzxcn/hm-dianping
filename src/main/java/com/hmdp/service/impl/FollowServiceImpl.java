package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    /**
     * 查询对用户的关注状态 已关注/未关注
     *
     * @return boolean
     */
    @Override
    public Result queryIsFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        Integer count = this.query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    /**
     * 关注用户
     * @param followUserId
     * @param isFollow
     * @return ok
     */
    @Override
    public Result followUser(Long followUserId, boolean isFollow) {
        // 获取用户信息
        Long userId = UserHolder.getUser().getId();
        // 判断用户是否已关注当前用户
        if (isFollow) {
            // 未关注，执行关注操作 保存关注信息
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            save(follow);
        }else {
            // 已关注，执行取关操作 移除关注信息
            LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
            remove(queryWrapper.eq(Follow::getUserId,userId).eq(Follow::getFollowUserId,followUserId));
        }
        return Result.ok();
    }
}
