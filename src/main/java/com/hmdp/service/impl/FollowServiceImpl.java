package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */

@Service
@RequiredArgsConstructor
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    private final StringRedisTemplate stringRedisTemplate;

    private final IUserService userService;

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
     *
     * @param followUserId
     * @param isFollow
     * @return ok
     */
    @Override
    public Result followUser(Long followUserId, boolean isFollow) {
        // 获取用户信息
        Long userId = UserHolder.getUser().getId();
        String key = "follow:" + userId;
        String followUserIdString = String.valueOf(followUserId);
        // 判断用户是否已关注当前用户
        if (isFollow) {
            // 未关注，执行关注操作 保存关注信息
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSave = save(follow);
            if (isSave) {
                // 将当前用户关注信息存入redis缓存
                stringRedisTemplate.opsForSet().add(key, followUserIdString);
            }
        } else {
            // 已关注，执行取关操作 移除关注信息
            LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
            boolean isRemove = remove(queryWrapper.eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, followUserId));
            if (isRemove) {
                // 将当前用户关注信息从redis缓存中移除
                stringRedisTemplate.opsForSet().remove(key, followUserIdString);
            }
        }
        return Result.ok();
    }


    /**
     * 查看共同关注
     *
     * @param lookingUserId
     * @return List<UserDTO>
     */
    @Override
    public Result followCommon(Long lookingUserId) {
        // 查询当前用户的关注信息
        Long userId = UserHolder.getUser().getId();
        String key = "follow:" + userId;

        // 查询目标用户的关注信息
        String objectKey = "follow:" + lookingUserId;

        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, objectKey);
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> followCommonIds = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOS = userService.listByIds(followCommonIds)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
