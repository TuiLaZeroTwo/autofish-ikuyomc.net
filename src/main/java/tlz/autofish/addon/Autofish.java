package tlz.autofish.addon;

import tlz.autofish.addon.modules.AutoFish;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class Autofish extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("TLZ");

    @Override
    public void onInitialize() {
        LOG.info("Initializing TLZ AutoFish");

        Modules.get().add(new AutoFish());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "tlz.autofish.addon";
    }
}
