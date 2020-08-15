package com.brandon3055.draconicevolution.inventory;

import com.brandon3055.brandonscore.inventory.ContainerBCore;
import com.brandon3055.brandonscore.inventory.ContainerSlotLayout;
import com.brandon3055.brandonscore.lib.Pair;
import com.brandon3055.draconicevolution.api.capability.PropertyProvider;
import com.brandon3055.draconicevolution.client.gui.modular.itemconfig.PropertyData;
import com.brandon3055.draconicevolution.init.DEContent;
import com.brandon3055.draconicevolution.lib.WTFException;
import com.google.common.collect.Streams;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.container.*;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.common.util.LazyOptional;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.brandon3055.draconicevolution.api.capability.DECapabilities.PROPERTY_PROVIDER_CAPABILITY;

/**
 * Created by brandon3055 on 19/4/20.
 */
public class ContainerConfigurableItem extends ContainerBCore<Object> {
    private static final UUID DEFAULT_UUID = UUID.fromString("d12b41e3-16ce-4653-ab36-1cd913719af8"); //This is just a completely random UUID

    private UUID selectedId; //Default is irrelevant as long as its not null.
    private Runnable onInventoryChange;
    private Consumer<Boolean> onSelectionMade;
    private ItemStack stackCache = ItemStack.EMPTY;

    public ContainerConfigurableItem(int windowId, PlayerInventory player, PacketBuffer extraData, ContainerSlotLayout.LayoutFactory<Object> factory) {
        super(DEContent.container_configurable_item, windowId, player, extraData, factory);
        this.selectedId = DEFAULT_UUID;
    }

    public ContainerConfigurableItem(int windowId, PlayerInventory player, ContainerSlotLayout.LayoutFactory<Object> factory) {
        super(DEContent.container_configurable_item, windowId, player, factory);
        sanitizeProviders();
        UUID held = getProviderID(player.player.getHeldItemMainhand());
        this.selectedId = held == null ? DEFAULT_UUID : held;
    }

    private Stream<ItemStack> getInventoryStacks() {
//        //TODO add support for things like baubles
        return inventorySlots.stream()
                .map(Slot::getStack)
                .filter(stack -> !stack.isEmpty());
    }

    public void setOnInventoryChange(Runnable onInventoryChange) {
        this.onInventoryChange = onInventoryChange;
    }

    public void setSelectionListener(Consumer<Boolean> onSelectionMade) {
        this.onSelectionMade = onSelectionMade;
    }

    public static Stream<PropertyProvider> getProviders(Stream<ItemStack> stacks) {
        return stacks
                .map(e -> e.getCapability(PROPERTY_PROVIDER_CAPABILITY))
                .filter(LazyOptional::isPresent)
                .map(e -> e.orElseThrow(WTFException::new));
    }

    public PropertyProvider findProvider(UUID providerID) {
        return getProviders(getInventoryStacks())
                .filter(provider -> provider.getProviderID().equals(providerID))
                .findFirst()
                .orElse(null);
    }

    private void sanitizeProviders() {
        HashSet<UUID> uuids = new HashSet<>();
        getProviders(getInventoryStacks())
                .filter(provider -> !uuids.add(provider.getProviderID()))
                .forEach(PropertyProvider::regenProviderID);
    }

    @Override
    public ItemStack slotClick(int slotId, int button, ClickType clickTypeIn, PlayerEntity player) {
        if (slotId >= 0 && slotId < inventorySlots.size()) {
            Slot slot = this.inventorySlots.get(slotId);
            if (slot != null && !slot.getStack().isEmpty()) {
                LazyOptional<PropertyProvider> optionalCap = slot.getStack().getCapability(PROPERTY_PROVIDER_CAPABILITY);
                if (optionalCap.isPresent()) {
                    PropertyProvider provider = optionalCap.orElseThrow(WTFException::new);
                    if (clickTypeIn == ClickType.PICKUP && button == 0 && player.inventory.getItemStack().isEmpty()) {
                        selectedId = provider.getProviderID();
                        if (onSelectionMade != null) {
                            onSelectionMade.accept(false);
                        }
                        stackCache = slot.getStack();
                        return ItemStack.EMPTY;
                    }
                }
            }
        }
        ItemStack ret = super.slotClick(slotId, button, clickTypeIn, player);
        if (onInventoryChange != null) {
            onInventoryChange.run();
        }
        return ret;
    }

    public UUID getSelectedId() {
        return selectedId;
    }

    private UUID getProviderID(ItemStack stack) {
        LazyOptional<PropertyProvider> optionalCap = stack.getCapability(PROPERTY_PROVIDER_CAPABILITY);
        if (!stack.isEmpty() && optionalCap.isPresent()) {
            return optionalCap.orElseThrow(WTFException::new).getProviderID();
        }
        return null;
    }

    private static Stream<ItemStack> getPlayerInventory(PlayerInventory player) {
        return Streams.concat(player.mainInventory.stream(), player.armorInventory.stream(), player.offHandInventory.stream()).filter(e -> !e.isEmpty());
    }

    public static Stream<Pair<ItemStack, PropertyProvider>> getStackProviders(Stream<ItemStack> stacks) {
        return stacks
                .map(e -> Pair.of(e, e.getCapability(PROPERTY_PROVIDER_CAPABILITY)))
                .filter(e -> e.value().isPresent())
                .map(e -> Pair.of(e.key(), e.value().orElseThrow(WTFException::new)));
    }

    public static void handlePropertyData(PlayerEntity player, PropertyData data) {
        if (data.isGlobal) {
            getStackProviders(getPlayerInventory(player.inventory))
                    .filter(e -> e.value().getProviderName().equals(data.providerName))
                    .map(e -> Pair.of(e.key(), e.value().getProperty(data.getPropertyName())))
                    .filter(e -> Objects.nonNull(e.value()))
                    .filter(e -> e.value().getType() == data.type)
                    .forEach(e -> e.value().loadData(data, e.key()));
        } else {
            getStackProviders(getPlayerInventory(player.inventory))
                    .filter(e -> e.value().getProviderID().equals(data.providerID))
                    .map(e -> Pair.of(e.key(), e.value().getProperty(data.getPropertyName())))
                    .filter(e -> Objects.nonNull(e.value()))
                    .filter(e -> e.value().getType() == data.type)
                    .findAny()
                    .ifPresent(e -> e.value().loadData(data, e.key()));
        }
    }

    @Override
    public void setAll(List<ItemStack> stacks) {
        super.setAll(stacks);
        onSyncDataReceived();
    }

    @Override
    public void putStackInSlot(int slotID, ItemStack stack) {
        super.putStackInSlot(slotID, stack);
        onSyncDataReceived();
    }

    private boolean initialSync = false;

    private void onSyncDataReceived() {
        if (!initialSync) {
            UUID held = getProviderID(player.getHeldItemMainhand());
            this.selectedId = held == null ? DEFAULT_UUID : held;
            if (selectedId != DEFAULT_UUID) {
                stackCache = player.getHeldItemMainhand();
            }
            initialSync = true;
            if (onSelectionMade != null) {
                onSelectionMade.accept(true);
            }
        }
        if (onInventoryChange != null) {
            onInventoryChange.run();
        }
    }

    /**
     * Do not use this for anything important!
     * */
    public ItemStack getLastStack() {
        return stackCache;
    }

    public static class Provider implements INamedContainerProvider {
        @Override
        public ITextComponent getDisplayName() {
            return new TranslationTextComponent("gui.draconicevolution.configure_item.name");
        }

        @Nullable
        @Override
        public Container createMenu(int menuID, PlayerInventory playerInventory, PlayerEntity playerEntity) {
            return new ContainerConfigurableItem(menuID, playerInventory, GuiLayoutFactories.CONFIGURABLE_ITEM_LAYOUT);
        }
    }
}