package com.citylaundry.web;

import com.citylaundry.model.FactorySite;
import com.citylaundry.repo.FactorySiteRepo;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 元数据：工厂站点列表 */
@RestController
@RequestMapping("/api/meta")
public class MetaController {
    private final FactorySiteRepo siteRepo;

    public MetaController(FactorySiteRepo siteRepo) {
        this.siteRepo = siteRepo;
    }

    @GetMapping("/sites")
    public List<FactorySite> sites() {
        return siteRepo.findAll();
    }
}
