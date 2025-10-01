package main.ironbackpacks.events;

import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.registry.GameRegistry;
import main.ironbackpacks.IronBackpacks;
import main.ironbackpacks.ModInformation;
import main.ironbackpacks.container.backpack.ContainerBackpack;
import main.ironbackpacks.container.backpack.InventoryBackpack;
import main.ironbackpacks.handlers.ConfigHandler;
import main.ironbackpacks.items.backpacks.BackpackTypes;
import main.ironbackpacks.items.backpacks.IBackpack;
import main.ironbackpacks.items.backpacks.ItemBackpack;
import main.ironbackpacks.items.upgrades.UpgradeMethods;
import main.ironbackpacks.util.IronBackpacksHelper;
import main.ironbackpacks.util.Logger;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ContainerWorkbench;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerUseItemEvent;
import net.minecraftforge.oredict.OreDictionary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * All the events used that fire on the Forge Event bus
 */
public class ForgeEventHandler {

    private static final int PLAYER_INVENTORY_SIZE = 36;
    private static final BackpackTypes[] BACKPACK_TYPES = BackpackTypes.values();
    private static final Map<ItemStackKey, String> MOD_ID_CACHE = new HashMap<ItemStackKey, String>();
    private static final Map<ItemStackKey, List<String>> ORE_DICT_CACHE = new HashMap<ItemStackKey, List<String>>();
    private static final Set<ItemStackKey> EMPTY_ORE_DICT_CACHE = new HashSet<ItemStackKey>();
    private static final Map<RecipeKey, ItemStack> RECIPE_CACHE = new HashMap<RecipeKey, ItemStack>();

    /**
     * Called whenever an item is picked up by a player. The basis for all the filters, and the event used for the hopper/restocking and condenser/crafting upgrades too so it doesn't check too much and causes lag..
     * @param event - the event fired
     */
    @SubscribeEvent
    public void onItemPickupEvent(EntityItemPickupEvent event) {
        if (event.isCanceled())
            return; //ends the event
        else{
            ArrayList<ArrayList<ItemStack>> backpacks = getFilterCondenserAndHopperBackpacks(event.entityPlayer);
            boolean doFilter = checkHopperUpgradeItemPickup(event, backpacks.get(4)); //doFilter is false if the itemEntity is in the hopperUpgrade's slots and the itemEntity's stackSize < refillSize
            if (doFilter) {
                checkFilterUpgrade(event, backpacks.get(0)); //beware creative testing takes the itemstack still
            }
            for (int i = 1; i < 4; i++) {
                checkCondenserUpgrade(event, backpacks.get(i), i);//1x1, 2x2, and 3x3 condensers/crafters
            }
        }
    }

    /**
     * Called whenever the player uses an item. Used for the restocking(hopper) upgrade.
     * @param event - the event fired
     */
    @SubscribeEvent
    public void onPlayerItemUseEvent(PlayerUseItemEvent.Finish event){
        ItemStack resuppliedStack = null;
        if (!event.isCanceled()){
            ArrayList<ArrayList<ItemStack>> backpacks = getFilterCondenserAndHopperBackpacks(event.entityPlayer);
            resuppliedStack = checkHopperUpgradeItemUse(event, backpacks.get(2)); //reduce the stack in the backpack if you can refill and send back the refilled itemStack
            if (resuppliedStack != null) {
                event.result = resuppliedStack;
            }
        }
    }


    /**
     * When a player dies, check if player has any backpacks with keepOnDeathUpgrade so then they are saved for when they spawn
     * @param event - the event
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDeath(LivingDeathEvent event){
        if (!event.entity.worldObj.isRemote && event.entity instanceof EntityPlayer){ //server side
            IronBackpacks.proxy.saveBackpackOnDeath((EntityPlayer) event.entity);
        }
    }

    /**
     * When a player respawns, check if player had any backpacks with keepOnDeathUpgrade so then they spawn with them
     * @param event - the event
     */
    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event){
        if (!event.entity.worldObj.isRemote && event.entity instanceof EntityPlayer){ //server side
            IronBackpacks.proxy.loadBackpackOnDeath((EntityPlayer) event.entity);
        }
    }

    /**
     *  When the config is changed this will reload the changes to ensure it is correctly updated
     * @param event - the event
     */
    @SubscribeEvent
    public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (event.modID.equals(ModInformation.ID)) {
            ConfigHandler.syncConfig(false);
            Logger.info("Refreshing configuration file.");
        }
    }

    //============================================================================Helper Methods===============================================================================

    /**
     * Gets all the backpacks that have filter, condenser, or hopper upgrades in them for the EntityItemPickupEvent event.
     * @param player - the player to check
     * @return - a nested array list of the array lists of each type of backpack that has each filter type
     */
    private ArrayList<ArrayList<ItemStack>> getFilterCondenserAndHopperBackpacks(EntityPlayer player){
        ArrayList<ItemStack> filterBackpacks = new ArrayList<ItemStack>();
        ArrayList<ItemStack> condenserTinyBackpacks = new ArrayList<ItemStack>();
        ArrayList<ItemStack> condenserSmallBackpacks = new ArrayList<ItemStack>();
        ArrayList<ItemStack> condenserBackpacks = new ArrayList<ItemStack>();
        ArrayList<ItemStack> hopperBackpacks = new ArrayList<ItemStack>();
        ArrayList<ArrayList<ItemStack>> returnArray = new ArrayList<ArrayList<ItemStack>>();

        //get the equipped pack
        getEventBackpacks(IronBackpacks.proxy.getEquippedBackpack(player), filterBackpacks, condenserTinyBackpacks, condenserSmallBackpacks, condenserBackpacks, hopperBackpacks, player);

        //get the packs in the inventory
        for (int i = 0; i < player.inventory.getSizeInventory(); i++) {
            ItemStack stack = player.inventory.getStackInSlot(i);
            getEventBackpacks(stack, filterBackpacks, condenserTinyBackpacks, condenserSmallBackpacks, condenserBackpacks, hopperBackpacks, player);
        }

        returnArray.add(filterBackpacks);
        returnArray.add(condenserTinyBackpacks);
        returnArray.add(condenserSmallBackpacks);
        returnArray.add(condenserBackpacks);
        returnArray.add(hopperBackpacks);
        return returnArray;
    }

    private void getEventBackpacks(ItemStack backpack, ArrayList<ItemStack> filterBackpacks, ArrayList<ItemStack> condenserTinyBackpacks, ArrayList<ItemStack> condenserSmallBackpacks, ArrayList<ItemStack> condenserBackpacks, ArrayList<ItemStack> hopperBackpacks, EntityPlayer player){
        if (backpack != null && backpack.getItem() != null && backpack.getItem() instanceof IBackpack) {

            int[] upgrades = IronBackpacksHelper.getUpgradesAppliedFromNBT(backpack);
            addToLists(backpack, filterBackpacks, condenserTinyBackpacks, condenserSmallBackpacks, condenserBackpacks, hopperBackpacks, upgrades);

            if (UpgradeMethods.hasDepthUpgrade(upgrades)) {
                ItemBackpack itemBackpack = (ItemBackpack)backpack.getItem();
                BackpackTypes nestedType = getBackpackTypeByItemId(itemBackpack.getId());
                if (nestedType == null) {
                    return;
                }
                ContainerBackpack container = new ContainerBackpack(player, new InventoryBackpack(player, backpack, nestedType), nestedType);
                for (int j = 0; j < container.getInventoryBackpack().getSizeInventory(); j++) {
                    ItemStack nestedBackpack = container.getInventoryBackpack().getStackInSlot(j);
                    if (nestedBackpack != null && nestedBackpack.getItem() != null && nestedBackpack.getItem() instanceof IBackpack) {
                        addToLists(nestedBackpack, filterBackpacks, condenserTinyBackpacks, condenserSmallBackpacks, condenserBackpacks, hopperBackpacks, IronBackpacksHelper.getUpgradesAppliedFromNBT(nestedBackpack));
                    }
                }
            }
        }
    }

    private void addToLists(ItemStack stack, ArrayList<ItemStack> filterBackpacks, ArrayList<ItemStack> condenserTinyBackpacks, ArrayList<ItemStack> condenserSmallBackpacks, ArrayList<ItemStack> condenserBackpacks, ArrayList<ItemStack> hopperBackpacks, int[] upgrades){
        if (UpgradeMethods.hasFilterBasicUpgrade(upgrades) || UpgradeMethods.hasFilterModSpecificUpgrade(upgrades) ||
                UpgradeMethods.hasFilterFuzzyUpgrade(upgrades) || UpgradeMethods.hasFilterOreDictUpgrade(upgrades) ||
                UpgradeMethods.hasFilterAdvancedUpgrade(upgrades) || UpgradeMethods.hasFilterMiningUpgrade(upgrades)) {
            filterBackpacks.add(stack);
        }
        if (UpgradeMethods.hasCondenserTinyUpgrade(upgrades)) {
            condenserTinyBackpacks.add(stack);
        }
        if (UpgradeMethods.hasCondenserSmallUpgrade(upgrades)) {
            condenserSmallBackpacks.add(stack);
        }
        if (UpgradeMethods.hasCondenserUpgrade(upgrades)) {
            condenserBackpacks.add(stack);
        }
        if (UpgradeMethods.hasHopperUpgrade(upgrades)) {
            hopperBackpacks.add(stack);
        }
    }


    //TODO: cleanup the following two methods

    /**
     * Checks the hopper/restocking upgrade to try and refill items.
     * @param event - EntityItemPickupEvent
     * @param backpackStacks - the backpacks with this upgrade
     * @return - boolean successful
     */
    private boolean checkHopperUpgradeItemPickup(EntityItemPickupEvent event, ArrayList<ItemStack> backpackStacks){
        boolean doFilter = true;
        if (!backpackStacks.isEmpty()){
            for (ItemStack backpack : backpackStacks) {
                if (isBackpackContainerOpen(event.entityPlayer) || isItemEntityEmpty(event)) {
                    continue;
                }
                BackpackTypes type = getBackpackType(backpack);
                if (type == null) {
                    continue;
                }
                ContainerBackpack container = new ContainerBackpack(event.entityPlayer, new InventoryBackpack(event.entityPlayer, backpack, type), type);
                container.sort(); //TODO: test with this removed
                ArrayList<ItemStack> hopperItems = UpgradeMethods.getHopperItems(backpack);
                boolean shouldSave = false;
                for (ItemStack hopperItem : hopperItems) {
                    if (hopperItem == null) {
                        continue;
                    }

                    Slot slotToResupply = null;
                    ItemStack stackToResupply = null;

                    for (int i = type.getSize(); i < type.getSize() + PLAYER_INVENTORY_SIZE; i++){ //check player's inv for item
                        Slot tempSlot = (Slot) container.getSlot(i);
                        if (tempSlot != null && tempSlot.getHasStack()){
                            ItemStack tempItem = tempSlot.getStack();
                            if (IronBackpacksHelper.areItemsEqualAndStackable(tempItem, hopperItem)){ //found and less than max stack size
                                slotToResupply = tempSlot;
                                stackToResupply = tempItem;
                                break;
                            }
                        }
                    }

                    if (slotToResupply == null){
                        continue;
                    }

                    boolean done = false;
                    if (IronBackpacksHelper.areItemsEqualForStacking(event.item.getEntityItem(), stackToResupply)){
                        int amountToResupply = stackToResupply.getMaxStackSize() - stackToResupply.stackSize;
                        if (event.item.getEntityItem().stackSize >= amountToResupply) { //if larger size of stack on the ground than needed to resupply
                            event.item.setEntityItemStack(new ItemStack(event.item.getEntityItem().getItem(), event.item.getEntityItem().stackSize - amountToResupply, event.item.getEntityItem().getItemDamage()));
                            slotToResupply.putStack(new ItemStack(stackToResupply.getItem(), stackToResupply.getMaxStackSize(), stackToResupply.getItemDamage()));
                            done = true;
                            shouldSave = true;
                        }else { //just resupply what you can, it will automatically go into the player's slot needed
                            doFilter = false;
                        }
                    }
                    if (!done) { //then resupply from the backpack (if necessary)
                        for (int i = 0; i < type.getSize(); i++) {
                            Slot tempSlot = (Slot) container.getSlot(i);
                            if (tempSlot != null && tempSlot.getHasStack()) {
                                ItemStack tempItem = tempSlot.getStack();
                                if (IronBackpacksHelper.areItemsEqualForStacking(tempItem, stackToResupply)) {
                                    int amountToResupply;
                                    if (IronBackpacksHelper.areItemsEqualForStacking(event.item.getEntityItem(), stackToResupply)) {
                                        amountToResupply = stackToResupply.getMaxStackSize() - stackToResupply.stackSize - event.item.getEntityItem().stackSize;
                                        if (tempItem.stackSize >= amountToResupply) {
                                            tempSlot.decrStackSize(amountToResupply);
                                            slotToResupply.putStack(new ItemStack(stackToResupply.getItem(), stackToResupply.getMaxStackSize() - event.item.getEntityItem().stackSize, stackToResupply.getItemDamage()));
                                            shouldSave = true;
                                            break;
                                        } else {
                                            tempSlot.decrStackSize(tempItem.stackSize);
                                            slotToResupply.putStack(new ItemStack(stackToResupply.getItem(), stackToResupply.stackSize + tempItem.stackSize, stackToResupply.getItemDamage()));
                                        }
                                    }else{
                                        amountToResupply = stackToResupply.getMaxStackSize() - stackToResupply.stackSize;
                                        if (tempItem.stackSize >= amountToResupply) {
                                            tempSlot.decrStackSize(amountToResupply);
                                            slotToResupply.putStack(new ItemStack(stackToResupply.getItem(), stackToResupply.getMaxStackSize(), stackToResupply.getItemDamage()));
                                            shouldSave = true;
                                            break;
                                        } else {
                                            tempSlot.decrStackSize(tempItem.stackSize);
                                            slotToResupply.putStack(new ItemStack(stackToResupply.getItem(), stackToResupply.stackSize + tempItem.stackSize, stackToResupply.getItemDamage()));
                                        }
                                    }
                                    shouldSave = true;
                                }
                            }
                        }
                    }
                }
                if (shouldSave) {
                    container.sort(); //TODO: test and add to other events (add boolean so that it doesn't save when nothing changes)
                    container.onContainerClosed(event.entityPlayer);
                }
            }
        }
        return doFilter;
    }

    /**
     * Checks the hopper/restocking upgrade to try and refill items. Decrements from the backpack's stacks and updates the appropriate slot/stack in the player's inventory.
     * for each backpack
     *  if backpack has itemUsed in filter
     *      if backpack has itemUsed in inv
     *          resupply itemUsed
     *              get rid of backpackStack
     *              return new size of itemUsed stack
     * @param event - PlayerUseItemEvent.Finish
     * @param backpackStacks - the backpacks with this upgrade
     */
    private ItemStack checkHopperUpgradeItemUse(PlayerUseItemEvent.Finish event, ArrayList<ItemStack> backpackStacks){
        if (!backpackStacks.isEmpty()){
            for (ItemStack backpack : backpackStacks) {
                if (event.item == null || isBackpackContainerOpen(event.entityPlayer)) {
                    continue;
                }
                BackpackTypes type = getBackpackType(backpack);
                if (type == null) {
                    continue;
                }
                ContainerBackpack container = new ContainerBackpack(event.entityPlayer, new InventoryBackpack(event.entityPlayer, backpack, type), type);
                container.sort(); //TODO: test with this removed
                ArrayList<ItemStack> hopperItems = UpgradeMethods.getHopperItems(backpack);
                for (ItemStack hopperItem : hopperItems) {
                    if (hopperItem == null || !IronBackpacksHelper.areItemsEqualForStacking(event.item, hopperItem)) {
                        continue;
                    }

                    Slot slotToResupply = null;
                    ItemStack stackToResupply = null;

                    for (int i = type.getSize(); i < type.getSize() + PLAYER_INVENTORY_SIZE; i++){ //check player's inv for item (backpack size + 36 for player inv)
                        Slot tempSlot = (Slot) container.getSlot(i);
                        if (tempSlot!= null && tempSlot.getHasStack()){
                            ItemStack tempItem = tempSlot.getStack();
                            if (IronBackpacksHelper.areItemsEqualAndStackable(tempItem, hopperItem)){ //found and less than max stack size
                                slotToResupply = tempSlot;
                                stackToResupply = tempItem;
                                break;
                            }
                        }
                    }

                    if (slotToResupply == null){
                        continue;
                    }

                    for (int i = 0; i < type.getSize(); i++) {
                        Slot backpackSlot = (Slot) container.getSlot(i);
                        if (backpackSlot != null && backpackSlot.getHasStack()) {
                            ItemStack backpackItemStack = backpackSlot.getStack();

                            if (IronBackpacksHelper.areItemsEqualForStacking(stackToResupply, backpackItemStack)) {
                                int amountToResupply = stackToResupply.getMaxStackSize() - stackToResupply.stackSize;

                                if (backpackItemStack.stackSize >= amountToResupply) {
                                    backpackSlot.decrStackSize(amountToResupply);
                                    container.sort();
                                    container.onContainerClosed(event.entityPlayer);
                                    return (new ItemStack(stackToResupply.getItem(), stackToResupply.getMaxStackSize(), stackToResupply.getItemDamage()));

                                } else {
                                    backpackSlot.decrStackSize(backpackItemStack.stackSize);
                                    container.sort();
                                    container.onContainerClosed(event.entityPlayer);
                                    return (new ItemStack(stackToResupply.getItem(), stackToResupply.stackSize + backpackItemStack.stackSize, stackToResupply.getItemDamage()));
                                }
                            }
                        }
                    }
                }
            } //no save b/c returns and saves if it does anything
        }
        return null;
    }

    /**
     * Checks the backpacks with the condenser/crafting upgrade to craft the specified items
     * @param event - EntityItemPickupEvent
     * @param backpackStacks - the backpacks with the condenser upgrade
     * @param craftingGridDiameterToFill - The size of the crafting grid to try filling with (1x1 or 2x2 or 3x3)
     */
    private void checkCondenserUpgrade(EntityItemPickupEvent event, ArrayList<ItemStack> backpackStacks, int craftingGridDiameterToFill){
        if (backpackStacks.isEmpty() || isItemEntityEmpty(event) || event.entityPlayer == null || event.item == null || event.item.worldObj == null){
            return;
        }

        ContainerWorkbench containerWorkbench = new ContainerWorkbench(event.entityPlayer.inventory, event.item.worldObj, 0, 0, 0);
        InventoryCrafting inventoryCrafting = new InventoryCrafting(containerWorkbench, 3, 3);

        for (ItemStack backpack : backpackStacks) {
            if (backpack == null || isBackpackContainerOpen(event.entityPlayer)) {
                continue;
            }

            BackpackTypes type = getBackpackType(backpack);
            if (type == null) {
                continue;
            }

            ContainerBackpack container = new ContainerBackpack(event.entityPlayer, new InventoryBackpack(event.entityPlayer, backpack, type), type);
            container.sort(); //sort to make sure all items are in their smallest slot numbers possible

            int lastSlotIndex = container.getInventoryBackpack().getSizeInventory() - 1;
            if (lastSlotIndex >= 0 && container.getInventoryBackpack().getStackInSlot(lastSlotIndex) != null){ //backpack full, skip expensive crafting
                continue;
            }

            ArrayList<ItemStack> condenserItems;
            switch (craftingGridDiameterToFill){
                case 1:
                    condenserItems = UpgradeMethods.getCondenserTinyItems(backpack);
                    break;
                case 2:
                    condenserItems = UpgradeMethods.getCondenserSmallItems(backpack);
                    break;
                case 3:
                    condenserItems = UpgradeMethods.getCondenserItems(backpack);
                    break;
                default: //should be unreachable
                    condenserItems = UpgradeMethods.getCondenserItems(backpack);
                    Logger.error("IronBackpacks CraftingUpgrade Error, will probably give the wrong output");
            }

            boolean shouldSave = false;
            for (ItemStack condenserItem : condenserItems) {
                if (condenserItem == null) {
                    continue;
                }
                for (int index = 0; index < type.getSize(); index++) {
                    Slot theSlot = (Slot) container.getSlot(index);
                    if (theSlot == null || !theSlot.getHasStack()) {
                        continue;
                    }
                    ItemStack theStack = theSlot.getStack();
                    if (theStack == null || theStack.stackSize < (craftingGridDiameterToFill * craftingGridDiameterToFill) || !IronBackpacksHelper.areItemsEqualForStacking(theStack, condenserItem)) {
                        continue;
                    }

                    ItemStack recipeOutput = getCachedRecipeOutput(theStack, craftingGridDiameterToFill, inventoryCrafting, event.item.worldObj);
                    if (recipeOutput == null) {
                        continue;
                    }

                    boolean crafted = false;
                    int numberOfIterations = (int) Math.floor(theStack.stackSize / (craftingGridDiameterToFill * craftingGridDiameterToFill));
                    int numberOfItems = recipeOutput.stackSize * numberOfIterations;

                    if (numberOfItems > 64){ //multiple stacks, need to make sure there is room
                        for (int i = 0; i < numberOfIterations; i++){ //for every possible crafting operation
                            ItemStack myRecipeOutput = new ItemStack(recipeOutput.getItem(), recipeOutput.stackSize, recipeOutput.getItemDamage()); //get the output
                            ItemStack stack = container.transferStackInSlot(myRecipeOutput); //try to put that output into the backpack
                            if (stack == null){ //can't put it anywhere
                                break;
                            }else if (stack.stackSize != 0){ //remainder present, stack couldn't be fully transferred, undo the last operation
                                Slot slot = container.getSlot(container.getType().getSize()-1); //last slot in pack
                                slot.putStack(new ItemStack(recipeOutput.getItem(), recipeOutput.getMaxStackSize()-(recipeOutput.stackSize - stack.stackSize), recipeOutput.getItemDamage()));
                                crafted = true;
                                break;
                            } else { //normal condition, stack was fully transferred
                                theSlot.decrStackSize(1);
                                crafted = true;
                            }
                        }
                    }else {
                        ItemStack myRecipeOutput = new ItemStack(recipeOutput.getItem(), numberOfItems, recipeOutput.getItemDamage());
                        if (container.transferStackInSlot(myRecipeOutput) != null) {
                            theSlot.decrStackSize(theStack.stackSize - (theStack.stackSize % (craftingGridDiameterToFill * craftingGridDiameterToFill)));
                            crafted = true;
                        }
                    }

                    if (crafted) {
                        shouldSave = true;
                        container.save(event.entityPlayer);
                    }
                }
            }
            if (shouldSave) { //TODO: test
                container.sort(); //sort items
                container.onContainerClosed(event.entityPlayer);
            }
        }
    }


    //===================================================================Filter Upgrade======================================================================

    /**
     * Checks the filters to see what items should be picked up and put in the backpack(s).
     * @param event - EntityItemPickupEvent
     * @param backpackStacks - the backpacks with a filter
     */
    private void checkFilterUpgrade(EntityItemPickupEvent event, ArrayList<ItemStack> backpackStacks){
        if (isItemEntityEmpty(event) || backpackStacks.isEmpty()){
            return;
        }
        for (ItemStack backpack : backpackStacks) {
            BackpackTypes type = getBackpackType(backpack);
            if (type == null) {
                continue;
            }
            ContainerBackpack container = new ContainerBackpack(event.entityPlayer, new InventoryBackpack(event.entityPlayer, backpack, type), type);
            if (isBackpackContainerOpen(event.entityPlayer)) { //can't have the backpack open
                continue;
            }
            int[] upgrades = IronBackpacksHelper.getUpgradesAppliedFromNBT(backpack);

            if (UpgradeMethods.hasFilterBasicUpgrade(upgrades))
                transferWithBasicFilter(UpgradeMethods.getBasicFilterItems(backpack), event, container);

            if (UpgradeMethods.hasFilterModSpecificUpgrade(upgrades))
                transferWithModSpecificFilter(UpgradeMethods.getModSpecificFilterItems(backpack), event, container);

            if (UpgradeMethods.hasFilterFuzzyUpgrade(upgrades))
                transferWithFuzzyFilter(UpgradeMethods.getFuzzyFilterItems(backpack), event, container);

            if (UpgradeMethods.hasFilterOreDictUpgrade(upgrades))
                transferWithOreDictFilter(UpgradeMethods.getOreDictFilterItems(backpack), getOreDict(event.item.getEntityItem()), event, container);

            if (UpgradeMethods.hasFilterAdvancedUpgrade(upgrades)) {
                ItemStack[] advFilterItems = UpgradeMethods.getAdvFilterAllItems(backpack);
                byte[] advFilterButtonStates = UpgradeMethods.getAdvFilterButtonStates(backpack);

                transferWithBasicFilter(UpgradeMethods.getAdvFilterBasicItems(advFilterItems, advFilterButtonStates), event, container);
                transferWithModSpecificFilter(UpgradeMethods.getAdvFilterModSpecificItems(advFilterItems, advFilterButtonStates), event, container);
                transferWithFuzzyFilter(UpgradeMethods.getAdvFilterFuzzyItems(advFilterItems, advFilterButtonStates), event, container);
                transferWithOreDictFilter(UpgradeMethods.getAdvFilterOreDictItems(advFilterItems, advFilterButtonStates), getOreDict(event.item.getEntityItem()), event, container);
            }

            if (UpgradeMethods.hasFilterMiningUpgrade(upgrades))
                transferWithMiningFilter(UpgradeMethods.getMiningFilterItems(backpack), getOreDict(event.item.getEntityItem()), event, container);

            if (UpgradeMethods.hasFilterVoidUpgrade(upgrades))
                deleteWithVoidFilter(UpgradeMethods.getVoidFilterItems(backpack), event);

        }
    }

    /**
     * Transfers items with respect to exact matching.
     * @param filterItems - the itemstacks to check
     * @param event - EntityItemPickupEvent
     * @param container - the backpack to transfer items into
     */
    private void transferWithBasicFilter(ArrayList<ItemStack> filterItems, EntityItemPickupEvent event, ContainerBackpack container){
        if (isItemEntityEmpty(event)) {
            return;
        }
        boolean shouldSave = false;
        for (ItemStack filterItem : filterItems) {
            if (filterItem != null) {
                if (IronBackpacksHelper.areItemsEqualForStacking(event.item.getEntityItem(), filterItem)) {
                    container.transferStackInSlot(event.item.getEntityItem());
                    shouldSave = true;
                }
            }
        }
        if (shouldSave) container.onContainerClosed(event.entityPlayer);
    }

    /**
     * Transfers items ignoring damage values.
     * @param filterItems - the items to check
     * @param event - EntityItemPickupEvent
     * @param container - the backpack to transfer items into
     */
    private void transferWithFuzzyFilter(ArrayList<ItemStack> filterItems, EntityItemPickupEvent event, ContainerBackpack container){
        if (isItemEntityEmpty(event)) {
            return;
        }
        boolean shouldSave = false;
        for (ItemStack filterItem : filterItems) {
            if (filterItem != null) {
                if (event.item.getEntityItem().getItem() == filterItem.getItem()) {
                    container.transferStackInSlot(event.item.getEntityItem()); //custom method to put itemEntity's itemStack into the backpack
                    shouldSave = true;
                }
            }
        }
        if (shouldSave) container.onContainerClosed(event.entityPlayer);
    }

    /**
     * Transfers items with respect to the ore dictionary
     * @param filterItems - the items to check
     * @param itemEntityOre - the ore dictionary entry of the item
     * @param event - EntityItemPickupEvent
     * @param container - the backpack to move items into
     */
    private void transferWithOreDictFilter(ArrayList<ItemStack> filterItems, ArrayList<String> itemEntityOre, EntityItemPickupEvent event, ContainerBackpack container){
        if (isItemEntityEmpty(event)) {
            return;
        }
        boolean shouldSave = false;
        for (ItemStack filterItem : filterItems) {
            if (filterItem != null) {
                ArrayList<String> filterItemOre = getOreDict(filterItem);
                if (itemEntityOre != null && filterItemOre != null) {
                    for (String oreName : itemEntityOre) {
                        if (oreName != null && filterItemOre.contains(oreName)) {
                            container.transferStackInSlot(event.item.getEntityItem()); //custom method to put itemEntity's itemStack into the backpack
                            shouldSave = true;
                        }
                    }
                }
            }
        }
        if (shouldSave) container.onContainerClosed(event.entityPlayer);
    }

    /**
     * Transfers items with respect to the category of the same mod
     * @param filterItems - the items to check
     * @param event - EntityItemPickupEvent
     * @param container - the backpack to move the items into
     */
    private void transferWithModSpecificFilter(ArrayList<ItemStack> filterItems, EntityItemPickupEvent event, ContainerBackpack container){
        if (isItemEntityEmpty(event)) {
            return;
        }
        boolean shouldSave = false;
        String eventModId = getModId(event.item.getEntityItem());
        for (ItemStack filterItem : filterItems) {
            if (filterItem != null) {
                String filterModId = getModId(filterItem);
                if (eventModId != null && eventModId.equals(filterModId)){
                    container.transferStackInSlot(event.item.getEntityItem());
                    shouldSave = true;
                }
            }
        }
        if (shouldSave) container.onContainerClosed(event.entityPlayer);
    }

    /**
     * Transfers items with ore in the name
     * @param filterItems - the items to check
     * @param event - EntityItemPickupEvent
     * @param container - the backpack to move the items into
     */
    private void transferWithMiningFilter(ArrayList<ItemStack> filterItems, ArrayList<String> itemEntityOre, EntityItemPickupEvent event, ContainerBackpack container){
        if (isItemEntityEmpty(event)) {
            return;
        }
        boolean shouldSave = false;
        transferWithBasicFilter(filterItems, event, container);
        if (itemEntityOre != null) {
            for (String oreName : itemEntityOre) {
                //TODO: fancier checking method, this is a 'contains' so it will get extra items ex: 'mining c*ore*'
                if (oreName != null && (oreName.contains("ore") || oreName.contains("gem") || oreName.contains("dust"))) {
                    container.transferStackInSlot(event.item.getEntityItem()); //custom method to put itemEntity's itemStack into the backpack
                    shouldSave = true;
                }
            }
        }
        if (shouldSave) container.onContainerClosed(event.entityPlayer);
    }

    /**
     * Deletes items in the void filter by destroying the entityItem picked up intead of moving it into the backpack or elsewhere
     * @param filterItems - the items to delete
     * @param event - EntityItemPickupEvent
     */
    private void deleteWithVoidFilter(ArrayList<ItemStack> filterItems, EntityItemPickupEvent event){
        if (isItemEntityEmpty(event)) {
            return;
        }
        for (ItemStack stack : filterItems) {
            if (stack != null) {
                if (IronBackpacksHelper.areItemsEqualForStacking(stack, event.item.getEntityItem())){ //if same item
                    event.item.setDead(); //delete it
                    event.item.onUpdate(); //update to make sure it's gone
                    event.setCanceled(true); //make sure it can't be picked up by other mods/vanilla
                }
            }
        }
    }

    /**
     * Gets the ore dictionary entries from an item
     * @param itemStack - the item to check
     * @return - OreDict entries in string form, null if no entries
     */
    private ArrayList<String> getOreDict(ItemStack itemStack){
        if (itemStack == null || itemStack.getItem() == null) {
            return null;
        }
        ItemStackKey key = new ItemStackKey(itemStack);
        if (ORE_DICT_CACHE.containsKey(key)) {
            return new ArrayList<String>(ORE_DICT_CACHE.get(key));
        }
        if (EMPTY_ORE_DICT_CACHE.contains(key)) {
            return null;
        }
        int[] ids = OreDictionary.getOreIDs(itemStack);
        ArrayList<String> retList = new ArrayList<String>();
        if (ids.length > 0){
            for (int i = 0; i < ids.length; i++) {
                String oreName = OreDictionary.getOreName(ids[i]);
                if (oreName != null && (i == 0 || !retList.contains(oreName))) {
                    retList.add(oreName);
                }
            }
        }
        if (retList.isEmpty()) {
            EMPTY_ORE_DICT_CACHE.add(key);
            return null;
        }
        ArrayList<String> cached = new ArrayList<String>(retList);
        ORE_DICT_CACHE.put(key, cached);
        return new ArrayList<String>(cached);
    }

    private boolean isItemEntityEmpty(EntityItemPickupEvent event) {
        return event.item == null || event.item.isDead || event.item.getEntityItem() == null || event.item.getEntityItem().stackSize <= 0;
    }

    private boolean isBackpackContainerOpen(EntityPlayer player) {
        return player != null && player.openContainer instanceof ContainerBackpack;
    }

    private BackpackTypes getBackpackType(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemBackpack)) {
            return null;
        }
        ItemBackpack itemBackpack = (ItemBackpack) stack.getItem();
        int guiId = itemBackpack.getGuiId();
        if (guiId < 0 || guiId >= BACKPACK_TYPES.length) {
            return null;
        }
        return BACKPACK_TYPES[guiId];
    }

    private BackpackTypes getBackpackTypeByItemId(int itemId) {
        int index = itemId - 1;
        if (index < 0 || index >= BACKPACK_TYPES.length) {
            return null;
        }
        return BACKPACK_TYPES[index];
    }

    private String getModId(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return null;
        }
        ItemStackKey key = new ItemStackKey(stack);
        if (MOD_ID_CACHE.containsKey(key)) {
            return MOD_ID_CACHE.get(key);
        }
        GameRegistry.UniqueIdentifier identifier = GameRegistry.findUniqueIdentifierFor(stack.getItem());
        String modId = identifier == null ? null : identifier.modId;
        MOD_ID_CACHE.put(key, modId);
        return modId;
    }

    private ItemStack getCachedRecipeOutput(ItemStack stack, int craftingGridDiameterToFill, InventoryCrafting inventoryCrafting, net.minecraft.world.World world) {
        if (stack == null || stack.getItem() == null || world == null || inventoryCrafting == null) {
            return null;
        }
        RecipeKey key = new RecipeKey(stack, craftingGridDiameterToFill);
        if (RECIPE_CACHE.containsKey(key)) {
            ItemStack cached = RECIPE_CACHE.get(key);
            return cached == null ? null : cached.copy();
        }

        clearInventoryCrafting(inventoryCrafting);
        ItemStack myStack = new ItemStack(stack.getItem(), 1, stack.getItemDamage());
        int maxSlots = craftingGridDiameterToFill * craftingGridDiameterToFill;
        if (craftingGridDiameterToFill == 2) {
            inventoryCrafting.setInventorySlotContents(0, myStack);
            inventoryCrafting.setInventorySlotContents(1, myStack);
            inventoryCrafting.setInventorySlotContents(3, myStack);
            inventoryCrafting.setInventorySlotContents(4, myStack);
        } else {
            for (int i = 0; i < maxSlots; i++) {
                inventoryCrafting.setInventorySlotContents(i, myStack);
            }
        }

        ItemStack recipeOutput = CraftingManager.getInstance().findMatchingRecipe(inventoryCrafting, world);
        clearInventoryCrafting(inventoryCrafting);

        if (recipeOutput != null) {
            ItemStack cacheValue = recipeOutput.copy();
            RECIPE_CACHE.put(key, cacheValue);
            return recipeOutput.copy();
        }

        RECIPE_CACHE.put(key, null);
        return null;
    }

    private void clearInventoryCrafting(InventoryCrafting inventoryCrafting) {
        for (int i = 0; i < inventoryCrafting.getSizeInventory(); i++) {
            inventoryCrafting.setInventorySlotContents(i, null);
        }
    }

    private static class ItemStackKey {
        private final Item item;
        private final int damage;
        private final NBTTagCompound tag;
        private final int hashCode;

        private ItemStackKey(ItemStack stack) {
            this.item = stack.getItem();
            this.damage = stack.getItemDamage();
            this.tag = stack.hasTagCompound() ? (NBTTagCompound) stack.getTagCompound().copy() : null;
            int result = Item.getIdFromItem(this.item);
            result = 31 * result + this.damage;
            result = 31 * result + (this.tag != null ? this.tag.hashCode() : 0);
            this.hashCode = result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ItemStackKey)) {
                return false;
            }
            ItemStackKey other = (ItemStackKey) obj;
            if (this.item != other.item || this.damage != other.damage) {
                return false;
            }
            if (this.tag == null) {
                return other.tag == null;
            }
            return this.tag.equals(other.tag);
        }

        @Override
        public int hashCode() {
            return this.hashCode;
        }
    }

    private static class RecipeKey {
        private final ItemStackKey itemKey;
        private final int gridDiameter;
        private final int hashCode;

        private RecipeKey(ItemStack stack, int gridDiameter) {
            this.itemKey = new ItemStackKey(stack);
            this.gridDiameter = gridDiameter;
            int result = this.itemKey.hashCode();
            result = 31 * result + this.gridDiameter;
            this.hashCode = result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof RecipeKey)) {
                return false;
            }
            RecipeKey other = (RecipeKey) obj;
            if (this.gridDiameter != other.gridDiameter) {
                return false;
            }
            return this.itemKey.equals(other.itemKey);
        }

        @Override
        public int hashCode() {
            return this.hashCode;
        }
    }

}
