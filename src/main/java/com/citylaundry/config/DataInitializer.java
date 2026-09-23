package com.citylaundry.config;

import com.citylaundry.model.*;
import com.citylaundry.model.Enums.Role;
import com.citylaundry.repo.*;
import com.citylaundry.security.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** 首次启动写入演示数据：门店、工厂站点、各角色账号、会员卡 */
@Component
public class DataInitializer implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserAccountRepo userRepo;
    private final StoreRepo storeRepo;
    private final FactorySiteRepo siteRepo;
    private final MemberCardRepo cardRepo;

    public DataInitializer(UserAccountRepo userRepo, StoreRepo storeRepo,
                           FactorySiteRepo siteRepo, MemberCardRepo cardRepo) {
        this.userRepo = userRepo;
        this.storeRepo = storeRepo;
        this.siteRepo = siteRepo;
        this.cardRepo = cardRepo;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepo.count() > 0) {
            log.info("演示数据已存在，跳过初始化");
            return;
        }
        log.info("初始化演示数据...");

        Store s1 = new Store();
        s1.setName("市中心旗舰店");
        s1.setAcceptHighRisk(true);
        s1.setMaxHighRiskPerDay(5);
        s1 = storeRepo.save(s1);

        Store s2 = new Store();
        s2.setName("高新社区店");
        s2.setAcceptHighRisk(false); // 不接收高风险衣物：影响接单策略
        s2.setMaxHighRiskPerDay(0);
        storeRepo.save(s2);

        FactorySite site1 = new FactorySite();
        site1.setName("中央洗护工厂-1号站");
        site1.setSpecialty("通用洗护");
        site1 = siteRepo.save(site1);

        FactorySite site2 = new FactorySite();
        site2.setName("皮具护理站-2号站");
        site2.setSpecialty("皮具护理");
        site2 = siteRepo.save(site2);

        FactorySite site3 = new FactorySite();
        site3.setName("奢侈品护理站-3号站");
        site3.setSpecialty("奢侈品护理");
        siteRepo.save(site3);

        UserAccount customer1 = user("customer1", "pass123", "张伟", Role.CUSTOMER, null, null);
        UserAccount customer2 = user("customer2", "pass123", "李娜", Role.CUSTOMER, null, null);
        user("picker1", "pass123", "王强", Role.PICKUP, s1.getId(), null);
        user("factory1", "pass123", "赵敏", Role.FACTORY, null, site1.getId());
        user("cs1", "pass123", "陈静", Role.CS, null, null);
        user("courier1", "pass123", "刘洋", Role.DELIVERY, null, null);
        user("finance1", "pass123", "孙丽", Role.FINANCE, null, null);
        user("manager1", "pass123", "周杰", Role.STORE_MANAGER, s1.getId(), null);

        MemberCard c1 = new MemberCard();
        c1.setCardNo("MC1001");
        c1.setCustomerId(customer1.getId());
        c1.setBalance(new BigDecimal("500.00"));
        c1.setLevel("GOLD");
        cardRepo.save(c1);

        MemberCard c2 = new MemberCard();
        c2.setCardNo("MC1002");
        c2.setCustomerId(customer2.getId());
        c2.setBalance(new BigDecimal("200.00"));
        c2.setLevel("NORMAL");
        cardRepo.save(c2);

        log.info("演示数据初始化完成：2 门店 / 3 工厂站点 / 8 账号 / 2 会员卡");
    }

    private UserAccount user(String username, String password, String name, Role role,
                             Long storeId, Long siteId) {
        UserAccount u = new UserAccount();
        u.setUsername(username);
        u.setPasswordHash(AuthService.sha256(password));
        u.setName(name);
        u.setRole(role);
        u.setStoreId(storeId);
        u.setSiteId(siteId);
        return userRepo.save(u);
    }
}
