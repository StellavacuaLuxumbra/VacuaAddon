package com.vacua.addon;

import com.vacua.addon.modules.AntiCrash;
import com.vacua.addon.modules.AntiRun;
import com.vacua.addon.modules.AutoEz;
import com.vacua.addon.modules.AutoMapArt;
import com.vacua.addon.modules.ChasePlayer;
import com.vacua.addon.modules.FastFly;
import com.vacua.addon.modules.NoGround;
import com.vacua.addon.modules.NoLoadScreen;
import com.vacua.addon.modules.PhaseCheck;
import com.vacua.addon.modules.SetVelocity;
import com.vacua.addon.modules.TpBot;
import com.vacua.addon.modules.TpChase;
import com.vacua.addon.modules.Vclip;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VacuaAddon extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger("vacua-addon");
    public static final Category CATEGORY = new Category("Vacua");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Vacua Addon");

        Modules.get().add(new TpChase());
        Modules.get().add(new AutoMapArt());
        Modules.get().add(new FastFly());
        Modules.get().add(new ChasePlayer());
        Modules.get().add(new AntiRun());
        Modules.get().add(new TpBot());
        Modules.get().add(new AntiCrash());
        Modules.get().add(new NoLoadScreen());
        Modules.get().add(new NoGround());
        Modules.get().add(new AutoEz());
        Modules.get().add(new Vclip());
        Modules.get().add(new PhaseCheck());
        Modules.get().add(new SetVelocity());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.vacua.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("Vacua", "VacuaAddon");
    }
}
