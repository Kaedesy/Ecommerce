package com.macro.mall.portal.component;

import com.macro.mall.mapper.OmsOrderSettingMapper;
import com.macro.mall.model.OmsOrderSetting;
import com.macro.mall.portal.service.OmsPortalOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MallReconciliationWorkerTest {
    @Test
    void overduePendingNormalOrderUsesExistingSafeCancellationPath() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OmsOrderSettingMapper settings = mock(OmsOrderSettingMapper.class);
        OmsPortalOrderService orders = mock(OmsPortalOrderService.class);
        OmsOrderSetting setting = new OmsOrderSetting();
        setting.setNormalOrderOvertime(30);
        when(settings.selectByPrimaryKey(1L)).thenReturn(setting);
        doReturn(List.of(81L)).when(jdbc).queryForList(anyString(), eq(Long.class), any(Object[].class));
        doReturn(List.of()).when(jdbc).queryForList(anyString(), any(Object[].class));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(4);

        new MallReconciliationWorker(jdbc, settings, orders).reconcile();

        verify(orders).cancelOrder(81L);
        verify(jdbc).update(anyString(), eq("EXPIRED_PENDING_ORDER"), eq("81"), eq("AUTO_REPAIRED"), anyString());
    }
}
