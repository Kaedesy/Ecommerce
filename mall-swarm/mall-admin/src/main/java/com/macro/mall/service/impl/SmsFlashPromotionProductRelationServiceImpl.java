package com.macro.mall.service.impl;

import com.github.pagehelper.PageHelper;
import com.macro.mall.dao.SmsFlashPromotionProductRelationDao;
import com.macro.mall.dao.FlashPromotionRelationDao;
import com.macro.mall.dto.SmsFlashPromotionProduct;
import com.macro.mall.mapper.SmsFlashPromotionProductRelationMapper;
import com.macro.mall.model.SmsFlashPromotionProductRelation;
import com.macro.mall.model.SmsFlashPromotionProductRelationExample;
import com.macro.mall.service.SmsFlashPromotionProductRelationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.macro.mall.common.exception.Asserts;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 限时购商品关联管理Service实现类
 * Created by macro on 2018/11/16.
 */
@Service
public class SmsFlashPromotionProductRelationServiceImpl implements SmsFlashPromotionProductRelationService {
    @Autowired
    private SmsFlashPromotionProductRelationMapper relationMapper;
    @Autowired
    private SmsFlashPromotionProductRelationDao relationDao;
    @Autowired
    private FlashPromotionRelationDao flashRelationDao;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int create(List<SmsFlashPromotionProductRelation> relationList) {
        if (relationList == null || relationList.isEmpty()) {
            Asserts.fail("秒杀商品不能为空");
        }
        for (SmsFlashPromotionProductRelation relation : relationList) {
            if (relation.getProductId() == null || relation.getProductSkuId() == null
                    || relation.getFlashPromotionCount() == null || relation.getFlashPromotionCount() <= 0
                    || relation.getFlashPromotionLimit() == null || relation.getFlashPromotionLimit() <= 0
                    || relation.getFlashPromotionLimit() > relation.getFlashPromotionCount()
                    || relation.getFlashPromotionPrice() == null || relation.getFlashPromotionPrice().signum() < 0) {
                Asserts.fail("秒杀SKU、价格、配额或限购配置无效");
            }
            if (flashRelationDao.reserveSkuStock(relation.getProductSkuId(), relation.getProductId(), relation.getFlashPromotionId(), relation.getFlashPromotionSessionId(), relation.getFlashPromotionCount()) != 1) {
                Asserts.fail("SKU不存在、商品不匹配或可售库存不足，无法预留秒杀配额");
            }
            relation.setReservedCount(0);
            relation.setSoldCount(0);
            relation.setStockStatus("PREPARING");
            flashRelationDao.insertRelation(relation);
        }
        return relationList.size();
    }

@Override
    public int update(Long id, SmsFlashPromotionProductRelation relation) {
        Asserts.fail("秒杀SKU与配额创建后不可直接修改；请先结束活动并走库存释放流程");
        return 0;
    }

@Override
    @Transactional(rollbackFor = Exception.class)
    public int delete(Long id) {
        SmsFlashPromotionProductRelation relation = flashRelationDao.selectRelation(id);
        if (relation == null) return 0;
        if (!("PREPARING".equals(relation.getStockStatus()) || "READY".equals(relation.getStockStatus()))
                || (relation.getReservedCount() != null && relation.getReservedCount() != 0)
                || (relation.getSoldCount() != null && relation.getSoldCount() != 0)) {
            Asserts.fail("活动已开始或已有订单，不能删除该活动商品");
        }
        if (flashRelationDao.claimForDelete(id) != 1) Asserts.fail("活动状态已变化，请刷新后重试");
        String tag = "{" + id + "}";
        redisTemplate.opsForValue().set("flash:" + tag + ":status", "ENDED");
        if (flashRelationDao.releaseSkuStock(relation.getProductSkuId(), relation.getFlashPromotionCount()) != 1) {
            Asserts.fail("释放秒杀SKU预留库存失败");
        }
        int deleted = flashRelationDao.deletePreparing(id);
        if (deleted != 1) Asserts.fail("删除活动关联失败");
        redisTemplate.delete(java.util.Arrays.asList("flash:" + tag + ":stock", "flash:" + tag + ":users", "flash:" + tag + ":status"));
        return deleted;
    }

    @Override
    public SmsFlashPromotionProductRelation getItem(Long id) {
        return flashRelationDao.selectRelation(id);
    }

    @Override
    public List<SmsFlashPromotionProduct> list(Long flashPromotionId, Long flashPromotionSessionId, Integer pageSize, Integer pageNum) {
        PageHelper.startPage(pageNum,pageSize);
        return relationDao.getList(flashPromotionId,flashPromotionSessionId);
    }

    @Override
    public long getCount(Long flashPromotionId, Long flashPromotionSessionId) {
        SmsFlashPromotionProductRelationExample example = new SmsFlashPromotionProductRelationExample();
        example.createCriteria()
                .andFlashPromotionIdEqualTo(flashPromotionId)
                .andFlashPromotionSessionIdEqualTo(flashPromotionSessionId);
        return relationMapper.countByExample(example);
    }
}
