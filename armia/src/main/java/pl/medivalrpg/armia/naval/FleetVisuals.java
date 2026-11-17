// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.naval;

import io.papermc.paper.entity.TeleportFlag;
import java.util.*;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.joml.Matrix4f;
import pl.medivalrpg.armia.visual.ShipVisual;

/**
 * Raised solid deck: native scaled shulkers ride zero-sized carriers, above the boat's own collision box.
 * The look is an animated BetterModel ship ({@link ShipVisual}) laid exactly over that deck; without BetterModel
 * (or when the model is missing) the old vanilla BlockDisplay hull is built instead. Collision, helm, walkers and
 * exits never depend on which look is active.
 */
final class FleetVisuals {
    final Boat boat;
    final List<BlockDisplay> parts=new ArrayList<>();
    final List<Mannequin> crew=new ArrayList<>();
    final Set<UUID> walkers=new LinkedHashSet<>();
    private record Floor(Interaction carrier,Shulker collider,DeckRules.Tile tile) { }
    private final List<Floor> floor=new ArrayList<>();
    Interaction helm;
    private final List<float[]> geometry=new ArrayList<>();
    private final ShipRecord record;
    private final NamespacedKey key;
    private ShipVisual model;
    private long modeChecked;
    FleetVisuals(final ShipRecord record,final Location at,final NamespacedKey key) {
        this.record=record;this.key=key;
        this.boat=(Boat)at.getWorld().spawnEntity(at,EntityType.OAK_BOAT);
        this.boat.setPersistent(false);this.tag(this.boat);
        try {
        final var type=NavalRules.type(record.type);
        this.model=this.newModel(at);
        if(this.model==null)this.buildParts(at);
        this.helm=this.carrier(this.helmPoint());
        for(final DeckRules.Tile tile:DeckRules.tiles(type)) {
            final Location base=local(at,tile.x(),DeckRules.deckHeight(type)-tile.scale(),tile.z());
            final Interaction carrier=this.carrier(base);
            final Shulker shulker=at.getWorld().spawn(base,Shulker.class,e->{
                e.setAI(false);e.setGravity(false);e.setInvisible(true);e.setInvulnerable(true);e.setSilent(true);
                e.setPersistent(false);e.setPeek(0);e.setAttachedFace(BlockFace.DOWN);e.setCollidable(true);this.tag(e);
                final var scale=e.getAttribute(Attribute.SCALE);
                if(scale==null)throw new IllegalStateException("Brak natywnej skali pokładu.");scale.setBaseValue(tile.scale());
            });
            this.floor.add(new Floor(carrier,shulker,tile));
            if(!carrier.addPassenger(shulker))throw new IllegalStateException("Nie można zbudować kolizji pokładu.");
        }
        this.sync(0);
        } catch(RuntimeException ex){this.remove();throw ex;}
    }
    /** BetterModel ship over the deck, or null (block look). A model failure never blocks the physical ship. */
    private ShipVisual newModel(final Location at) {
        try {
            final ShipVisual created=ShipVisual.create(this.record.id,this.record.type,this.record.country,at);
            if(created!=null)this.boat.setInvisible(ShipVisual.hideBoat());
            return created;
        } catch(final RuntimeException|LinkageError ex) {return null;}
    }
    private void buildParts(final Location at) {
        final var type=NavalRules.type(this.record.type);
        final float w=(float)type.halfWidth(),l=(float)type.halfLength();
        final float deck=(float)DeckRules.deckHeight(type),lift=deck-.32f;
        this.piece(at,Material.DARK_OAK_PLANKS,-w+0.35f,-0.65f,-l+0.6f,w*2-0.7f,deck+.53f,l*2-1.2f);
        this.piece(at,Material.SPRUCE_PLANKS,-w,deck-.22f,-l+0.7f,w*2,.22f,l*2-1.4f);
        for(final float x:new float[]{-w,w-0.22f}) {
            this.piece(at,Material.DARK_OAK_PLANKS,x,-0.25f,-l+0.4f,0.22f,deck+.73f,l*2-0.8f);
            this.piece(at,Material.SPRUCE_FENCE,x,deck+.48f,-l+0.6f,0.18f,0.65f,l*2-1.2f);
        }
        this.piece(at,Material.DARK_OAK_PLANKS,-w+0.25f,-0.35f,-l,w*2-0.5f,deck+.48f,0.65f);
        this.piece(at,Material.DARK_OAK_PLANKS,-w+0.5f,-0.3f,l-0.5f,w*2-1,deck+.78f,0.8f);
        this.piece(at,Material.STRIPPED_SPRUCE_LOG,-0.12f,-0.4f,-l-0.5f,0.24f,0.4f,1.3f);
        this.piece(at,Material.BARREL,-w+0.4f,deck,-l+1,0.65f,0.65f,0.65f);
        this.piece(at,Material.BARREL,w-1.05f,deck,-l+1,0.65f,0.65f,0.65f);
        final int masts="karawela".equals(this.record.type)?2:1;
        for(int mast=0;mast<masts;mast++) {
            final float z=mast==0?1.1f:-2.5f,height=mast==0?6:4.8f;
            this.piece(at,Material.STRIPPED_SPRUCE_LOG,-0.15f,deck,z,0.3f,height,0.3f);
            this.piece(at,Material.STRIPPED_SPRUCE_LOG,-w-0.25f,height-0.2f+lift,z,w*2+0.5f,0.2f,0.22f);
            this.piece(at,Material.WHITE_WOOL,-w,height-3.1f+lift,z+0.1f,w*2,2.8f,0.12f);
            this.piece(at,Material.WHITE_WOOL,-w+0.4f,height-3.8f+lift,z+0.1f,w*2-0.8f,0.7f,0.12f);
            this.piece(at,Material.RED_WOOL,-0.1f,height+0.3f+lift,z,0.12f,0.65f,1.2f);
        }
        if("karawela".equals(this.record.type)) for(final float x:new float[]{-w+0.25f,w-0.75f})
            this.piece(at,Material.POLISHED_BLACKSTONE,x,deck+.08f,2,0.5f,0.5f,1.5f);
    }
    private void clearParts(){this.parts.forEach(Entity::remove);this.parts.clear();this.geometry.clear();}
    /** Switches between the model and the block look when BetterModel (re)loads or drops our models. */
    private void ensureMode(final Location at) {
        final long now=Bukkit.getCurrentTick();
        if(now-this.modeChecked<100)return;
        this.modeChecked=now;
        if(this.model!=null&&!this.model.alive()) {
            this.model.close();this.model=null;this.boat.setInvisible(false);this.buildParts(at);
        } else if(this.model==null&&ShipVisual.available(this.record.type)) {
            final ShipVisual created=this.newModel(at);
            if(created!=null){this.model=created;this.clearParts();}
        }
    }
    private Interaction carrier(final Location at){return at.getWorld().spawn(at,Interaction.class,e->{
        e.setInteractionWidth(0);e.setInteractionHeight(0);e.setResponsive(false);e.setGravity(false);
        e.setInvulnerable(true);e.setPersistent(false);this.tag(e);
    });}
    DeckRules.Frame frame(){final Location p=this.boat.getLocation();return new DeckRules.Frame(p.getX(),p.getY(),p.getZ(),p.getYaw());}
    Location entryPoint(){return local(this.boat.getLocation(),-.65,DeckRules.deckHeight(NavalRules.type(this.record.type))+.05,0);}
    Location helmPoint(){final var type=NavalRules.type(this.record.type);return local(this.boat.getLocation(),0,DeckRules.deckHeight(type)+.05,-type.halfLength()+1.5);}
    Player captain(){return this.helm==null?null:this.helm.getPassengers().stream().filter(Player.class::isInstance).map(Player.class::cast).findFirst().orElse(null);}
    /** The animated model, or null while the block look is shown. */
    ShipVisual model(){return this.model;}
    private void tag(final Entity entity) {entity.getPersistentDataContainer().set(this.key,PersistentDataType.STRING,this.record.id.toString());}
    private void piece(final Location at,final Material material,final float x,final float y,final float z,
                       final float sx,final float sy,final float sz) {
        // Minecraft yaw0 drives toward +Z. Mirror the old cosmetic bow so W really sails bow-first.
        this.geometry.add(new float[]{x,y,-z-sz,sx,sy,sz});
        this.parts.add(at.getWorld().spawn(at,BlockDisplay.class,e->{
            e.setBlock(material.createBlockData());e.setPersistent(false);e.setInvulnerable(true);e.setTeleportDuration(2);this.tag(e);
        }));
    }
    static Location local(final Location base,final double x,final double y,final double z) {
        final double yaw=Math.toRadians(base.getYaw());
        return base.clone().add(x*Math.cos(yaw)-z*Math.sin(yaw),y,x*Math.sin(yaw)+z*Math.cos(yaw));
    }
    void sync(final int soldiers) {
        final Location at=this.boat.getLocation();
        this.ensureMode(at);
        for(int i=0;i<this.parts.size();i++) {
            final float[] p=this.geometry.get(i);final BlockDisplay part=this.parts.get(i);
            part.teleport(at);part.setTransformationMatrix(new Matrix4f().translate(p[0],p[1],p[2]).scale(p[3],p[4],p[5]));
        }
        this.helm.teleport(this.helmPoint(),TeleportFlag.EntityState.RETAIN_PASSENGERS);
        for(final Floor f:this.floor)f.carrier.teleport(local(at,f.tile.x(),DeckRules.deckHeight(NavalRules.type(this.record.type))-f.tile.scale(),f.tile.z()),TeleportFlag.EntityState.RETAIN_PASSENGERS);
        final int shown=Math.min(4,soldiers);
        while(this.crew.size()>shown) this.crew.removeLast().remove();
        while(this.crew.size()<shown) this.crew.add(at.getWorld().spawn(at,Mannequin.class,e->{
            e.setImmovable(true);e.setGravity(false);e.setInvulnerable(true);e.setCollidable(false);e.setPersistent(false);
            e.getEquipment().setHelmet(new ItemStack(Material.CHAINMAIL_HELMET));
            e.getEquipment().setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
            e.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SWORD));this.tag(e);
        }));
        for(int i=0;i<this.crew.size();i++) this.crew.get(i).teleport(local(at,i%2==0?-0.75:0.75,DeckRules.deckHeight(NavalRules.type(this.record.type)),-2-(i/2)));
        if(this.model!=null)try{this.model.sync(at,this.captain());}catch(final RuntimeException ignored){ /* cosmetic only */ }
    }
    boolean valid(){return this.boat.isValid()&&this.helm!=null&&this.helm.isValid()
        &&this.floor.size()==DeckRules.tiles(NavalRules.type(this.record.type)).size()
        &&this.parts.stream().allMatch(Entity::isValid)
        &&this.floor.stream().allMatch(f->f.carrier.isValid()&&f.collider.isValid()&&f.collider.getVehicle()==f.carrier);}
    boolean contains(final Entity entity){return entity==this.boat||entity==this.helm||this.parts.contains(entity)||this.crew.contains(entity)
        ||this.floor.stream().anyMatch(f->entity==f.carrier||entity==f.collider);}
    List<Player> riders() {
        final List<Player> result=new ArrayList<>();
        final Player captain=this.captain();if(captain!=null)result.add(captain);
        for(final UUID id:this.walkers){final Player p=Bukkit.getPlayer(id);if(p!=null&&p.isOnline()&&!result.contains(p))result.add(p);}
        return result;
    }
    void remove() {if(this.model!=null){this.model.close();this.model=null;}this.parts.forEach(Entity::remove);this.floor.forEach(f->{f.collider.remove();f.carrier.remove();});this.floor.clear();if(this.helm!=null)this.helm.remove();this.crew.forEach(Entity::remove);this.walkers.clear();this.boat.remove();}
}
