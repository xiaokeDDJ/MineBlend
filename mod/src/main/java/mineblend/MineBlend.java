package mineblend;

import net.fabricmc.api.ModInitializer;
//? if <26.1 {
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
//?} else {
/*import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
*///?}
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

public class MineBlend implements ModInitializer {
    static final ResourceLocation WAND_ID = Mc.id("mineblend", "selection_wand");
    public static final Item SELECTION_WAND = new SelectionWandItem(wandProperties());

    private static Item.Properties wandProperties() {
        Item.Properties p = new Item.Properties().stacksTo(1).rarity(Rarity.RARE);
        //? if >=1.21.2 {
        /*p = p.setId(ResourceKey.create(Registries.ITEM, WAND_ID));
        *///?}
        return p;
    }

    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.ITEM, WAND_ID, SELECTION_WAND);
        //? if <26.1 {
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(e -> e.accept(SELECTION_WAND));
        //?} else {
        /*CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(e -> e.accept(SELECTION_WAND));
        *///?}
    }
}
