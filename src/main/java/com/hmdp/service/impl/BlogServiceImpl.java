package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {


    private final IUserService userService;

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            queryBlogUser(blog);
            blog.setIsLike(isBlogLiked(blog));
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = this.getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        queryBlogUser(blog);
        // 判断当前用户是否已经点赞
        blog.setIsLike(isBlogLiked(blog));
        return Result.ok(blog);
    }

    // 判断当前用户是否已经点赞
    private boolean isBlogLiked(Blog blog) {
        // 获取用户信息
        UserDTO user = UserHolder.getUser();
        if (user == null) return false;
        String userId = String.valueOf(user.getId());
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId);
        return score != null;
    }

    @Override
    public Result isLiked(Long id) {
        // 获取笔记
        Blog blog = getById(id);

        // 获取用户信息
        String userId = String.valueOf(UserHolder.getUser().getId());

        // 判断当前用户是否已经点赞
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        boolean isLiked = isBlogLiked(blog);
        if (!isLiked) {
            // 未点赞，点赞数+1
            boolean success = this.update().setSql("liked = liked + 1").eq("id", id).update();
            if (success) {
                // 写入redis
                stringRedisTemplate.opsForZSet().add(key, userId, System.currentTimeMillis());
            }
        } else {
            // 已点赞，点赞数-1
            boolean success = this.update().setSql("liked = liked - 1").eq("id", id).update();
            if (success) {
                // 从redis中移除
                stringRedisTemplate.opsForZSet().remove(key, userId);
            }
        }
        return Result.ok();
    }

    // 点赞排行榜top5
    @Override
    public Result queryBlogLikes(Long id) {
        // 根据笔记的id从redis中获取用户id
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Set<String> range = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (range == null || range.isEmpty()) return Result.ok(Collections.emptyList());
        List<Long> ids = range.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 3.根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        /*List<UserDTO> userDTOS = range.stream().map(userId -> {
            User user = userService.getById(userId);
            return BeanUtil.copyProperties(user, UserDTO.class);
        }).collect(Collectors.toList());*/
        return Result.ok(userDTOS);
    }

    // 查询笔记用户
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
