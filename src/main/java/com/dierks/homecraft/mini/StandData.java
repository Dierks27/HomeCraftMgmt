package com.dierks.homecraft.mini;

import com.dierks.homecraft.util.Items;
import org.bukkit.entity.ArmorStand;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.EulerAngle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stored configuration of a posed armor-stand Mini: the six body-part poses (in
 * degrees), the six equipment slots (Base64 items), and the display flags. This
 * is what an {@code ARMOR_STAND} Mini spawns from on placement. Captured from a
 * real, hand-posed armor stand in the world (authoring), and re-applied to the
 * spawned entity.
 */
public record StandData(double[] head, double[] body, double[] leftArm, double[] rightArm,
                        double[] leftLeg, double[] rightLeg,
                        String helmet, String chest, String legs, String boots,
                        String mainHand, String offHand,
                        boolean small, boolean arms, boolean basePlate, boolean invisible) {

    /** Read pose + equipment + flags from a real armor stand (authoring capture). */
    public static StandData capture(ArmorStand s) {
        EntityEquipment eq = s.getEquipment();
        return new StandData(
                deg(s.getHeadPose()), deg(s.getBodyPose()),
                deg(s.getLeftArmPose()), deg(s.getRightArmPose()),
                deg(s.getLeftLegPose()), deg(s.getRightLegPose()),
                b64(eq == null ? null : eq.getHelmet()),
                b64(eq == null ? null : eq.getChestplate()),
                b64(eq == null ? null : eq.getLeggings()),
                b64(eq == null ? null : eq.getBoots()),
                b64(eq == null ? null : eq.getItemInMainHand()),
                b64(eq == null ? null : eq.getItemInOffHand()),
                s.isSmall(), s.hasArms(), s.hasBasePlate(), s.isInvisible());
    }

    /** Apply this stored configuration to a freshly spawned armor stand. */
    public void applyTo(ArmorStand s) {
        s.setHeadPose(rad(head));
        s.setBodyPose(rad(body));
        s.setLeftArmPose(rad(leftArm));
        s.setRightArmPose(rad(rightArm));
        s.setLeftLegPose(rad(leftLeg));
        s.setRightLegPose(rad(rightLeg));
        s.setSmall(small);
        s.setArms(arms);
        s.setBasePlate(basePlate);
        s.setInvisible(invisible);
        EntityEquipment eq = s.getEquipment();
        if (eq != null) {
            eq.setHelmet(item(helmet));
            eq.setChestplate(item(chest));
            eq.setLeggings(item(legs));
            eq.setBoots(item(boots));
            eq.setItemInMainHand(item(mainHand));
            eq.setItemInOffHand(item(offHand));
        }
    }

    /** Serialize to a config map (degrees + Base64 equipment). */
    public Map<String, Object> toConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("small", small);
        m.put("arms", arms);
        m.put("base_plate", basePlate);
        m.put("invisible", invisible);
        Map<String, Object> pose = new LinkedHashMap<>();
        pose.put("head", toList(head));
        pose.put("body", toList(body));
        pose.put("left_arm", toList(leftArm));
        pose.put("right_arm", toList(rightArm));
        pose.put("left_leg", toList(leftLeg));
        pose.put("right_leg", toList(rightLeg));
        m.put("pose", pose);
        Map<String, Object> eq = new LinkedHashMap<>();
        eq.put("helmet", helmet);
        eq.put("chest", chest);
        eq.put("legs", legs);
        eq.put("boots", boots);
        eq.put("main_hand", mainHand);
        eq.put("off_hand", offHand);
        m.put("equipment", eq);
        return m;
    }

    /** Parse from a config map (as produced by {@link #toConfig()}). */
    @SuppressWarnings("unchecked")
    public static StandData fromConfig(Map<?, ?> m) {
        Map<?, ?> pose = m.get("pose") instanceof Map<?, ?> p ? p : Map.of();
        Map<?, ?> eq = m.get("equipment") instanceof Map<?, ?> e ? e : Map.of();
        return new StandData(
                angle(pose.get("head")), angle(pose.get("body")),
                angle(pose.get("left_arm")), angle(pose.get("right_arm")),
                angle(pose.get("left_leg")), angle(pose.get("right_leg")),
                str(eq.get("helmet")), str(eq.get("chest")), str(eq.get("legs")), str(eq.get("boots")),
                str(eq.get("main_hand")), str(eq.get("off_hand")),
                bool(m.get("small")), bool(m.get("arms"), true), bool(m.get("base_plate"), true),
                bool(m.get("invisible")));
    }

    // ---- helpers ----------------------------------------------------------

    private static double[] deg(EulerAngle a) {
        return new double[]{Math.toDegrees(a.getX()), Math.toDegrees(a.getY()), Math.toDegrees(a.getZ())};
    }

    private static EulerAngle rad(double[] d) {
        double[] v = (d != null && d.length == 3) ? d : new double[]{0, 0, 0};
        return new EulerAngle(Math.toRadians(v[0]), Math.toRadians(v[1]), Math.toRadians(v[2]));
    }

    private static String b64(ItemStack item) {
        return item == null || item.getType().isAir() ? "" : Items.toBase64(item);
    }

    private static ItemStack item(String data) {
        return data == null || data.isBlank() ? null : Items.fromBase64(data);
    }

    private static List<Double> toList(double[] d) {
        List<Double> out = new ArrayList<>(3);
        double[] v = (d != null && d.length == 3) ? d : new double[]{0, 0, 0};
        out.add(v[0]);
        out.add(v[1]);
        out.add(v[2]);
        return out;
    }

    private static double[] angle(Object o) {
        if (o instanceof List<?> l && l.size() == 3) {
            return new double[]{num(l.get(0)), num(l.get(1)), num(l.get(2))};
        }
        return new double[]{0, 0, 0};
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean bool(Object o) {
        return bool(o, false);
    }

    private static boolean bool(Object o, boolean def) {
        return o instanceof Boolean b ? b : def;
    }
}
