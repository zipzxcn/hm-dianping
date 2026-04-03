package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

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
            blog.setIsLike(isLiked(blog));
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
        blog.setIsLike(isLiked(blog));
        return Result.ok(blog);
    }

    // 判断当前用户是否已经点赞
    private boolean isLiked(Blog blog) {
        // 获取用户信息
        String userId = String.valueOf(UserHolder.getUser().getId());
        String key = "blog:liked:" + blog.getId();
        Boolean isLiked = stringRedisTemplate.opsForSet().isMember(key,userId);
        return Boolean.TRUE.equals(isLiked);
    }

    @Override
    public Result isLiked(Long id) {
        // 获取笔记
        Blog blog = getById(id);

        // 获取用户信息
        String userId = String.valueOf(UserHolder.getUser().getId());

        // 判断当前用户是否已经点赞
        String key = "blog:liked:" + id;
        boolean isLiked = isLiked(blog);
        if (!isLiked){
            // 未点赞，点赞数+1
            boolean success = this.update().setSql("liked = liked + 1").eq("id", id).update();
            if (success){
                // 写入redis
                stringRedisTemplate.opsForSet().add(key,userId);
            }
        }else {
            // 已点赞，点赞数-1
            boolean success = this.update().setSql("liked = liked - 1").eq("id", id).update();
            if(success){
                // 从redis中移除
                stringRedisTemplate.opsForSet().remove(key,userId);
            }
        }
        return Result.ok();
    }

    // 查询笔记用户
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
