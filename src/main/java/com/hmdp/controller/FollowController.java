package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/follow")
public class FollowController {

    private final IFollowService followService;

    @GetMapping("/or/not/{id}")
    public Result queryIsFollow(@PathVariable("id") Long followUserId){
        return followService.queryIsFollow(followUserId);
    }

    @PutMapping("/{id}/{boolean}")
    public Result followUser(@PathVariable("id") Long followUserId,@PathVariable("boolean") boolean isFollow){
        return followService.followUser(followUserId,isFollow);
    }


    @GetMapping("/common/{id}")
    public Result followCommon(@PathVariable("id") Long lookingUserId){
        return followService.followCommon(lookingUserId);
    }

}
