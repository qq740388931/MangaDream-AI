package com.example.imagetool.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Paths;

/**
 * 启动时确保 SQLite 数据库文件存在并创建 inspiration_template 表，若表为空则插入种子数据
 */
@Component
@Order(1)
public class SqliteInitRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public SqliteInitRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureDataDir();
        createTableIfNotExists();
        seedDataIfEmpty();
    }

    private void ensureDataDir() {
        File dir = Paths.get("data").toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private void createTableIfNotExists() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS inspiration_template (" +
                        "id INTEGER PRIMARY KEY," +
                        "title TEXT," +
                        "image_url TEXT," +
                        "description TEXT," +
                        "prompt TEXT," +
                        "sort_order INTEGER," +
                        "created_at TEXT," +
                        "updated_at TEXT" +
                        ")"
        );

        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS users (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "google_sub TEXT NOT NULL UNIQUE," +
                        "email TEXT," +
                        "name TEXT," +
                        "avatar_url TEXT," +
                        "created_at TEXT," +
                        "last_login_at TEXT," +
                        "points INTEGER," +
                        "is_vip INTEGER," +
                        "vip_expire_at TEXT" +
                        ")"
        );

        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS user_session (" +
                        "token TEXT PRIMARY KEY," +
                        "user_id INTEGER NOT NULL," +
                        "created_at TEXT," +
                        "last_used_at TEXT," +
                        "FOREIGN KEY(user_id) REFERENCES users(id)" +
                        ")"
        );

        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS feedback (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "username TEXT," +
                        "email TEXT," +
                        "content TEXT NOT NULL," +
                        "created_at TEXT NOT NULL" +
                        ")"
        );

        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS membership_request (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "user_id INTEGER," +
                        "username TEXT," +
                        "email TEXT," +
                        "plan_code TEXT," +
                        "status TEXT," +
                        "created_at TEXT," +
                        "updated_at TEXT," +
                        "admin_comment TEXT," +
                        "FOREIGN KEY(user_id) REFERENCES users(id)" +
                        ")"
        );

        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS generate_log (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "user_id INTEGER," +
                        "type TEXT," +
                        "ip TEXT," +
                        "user_agent TEXT," +
                        "success INTEGER," +
                        "reason TEXT," +
                        "history_id TEXT," +
                        "result_url TEXT," +
                        "created_at TEXT," +
                        "FOREIGN KEY(user_id) REFERENCES users(id)" +
                        ")"
        );

        try {
            jdbcTemplate.execute("ALTER TABLE generate_log ADD COLUMN history_id TEXT");
        } catch (Exception ignored) {
        }
        try {
            jdbcTemplate.execute("ALTER TABLE generate_log ADD COLUMN result_url TEXT");
        } catch (Exception ignored) {
        }

        // 尝试给旧的 users 表增加 points 列（如已存在会抛错，忽略即可）
        try {
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN points INTEGER");
        } catch (Exception ignored) {
        }
        try {
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN is_vip INTEGER");
        } catch (Exception ignored) {
        }
        try {
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN vip_expire_at TEXT");
        } catch (Exception ignored) {
        }
    }

    private void seedDataIfEmpty() {
        // 确保已有用户至少有 10 积分
        jdbcTemplate.update("UPDATE users SET points = 10 WHERE points IS NULL OR points < 10");

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inspiration_template", Integer.class);
        if (count == null || count > 0) {
            return;
        }
        String sql = "INSERT INTO inspiration_template (id, title, image_url, description, prompt, sort_order, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, 1, "", "/images/cyberpunk.png", null, getPrompt1(), 1, "2026-03-14 15:12:26", "2026-03-15 18:21:36");
        jdbcTemplate.update(sql, 2, "", "/images/placeholder.png", null, getPrompt2(), 2, "2026-03-14 15:12:26", "2026-03-14 15:12:26");
        jdbcTemplate.update(sql, 3, "", "/images/33333.jpg", null, getPrompt3(), 3, "2026-03-14 15:12:26", "2026-03-14 15:12:26");
        jdbcTemplate.update(sql, 4, "", "/images/placeholder.png", null, "oil painting style, brush strokes, rich texture, classic art, realistic lighting, canvas texture", 4, "2026-03-14 15:12:26", "2026-03-14 15:12:26");
        jdbcTemplate.update(sql, 5, "", "/images/placeholder.png", null, "Chinese ink wash painting, black and white, minimalist, traditional brushwork, landscape, Zen style", 5, "2026-03-14 15:12:26", "2026-03-14 15:12:26");
    }

    private static String getPrompt1() {
        return "masterpiece, best quality, ultra-detailed, 8k, realistic photo,\n"
                + "keep original character,\n"
                + "background: traditional chinese window lattice, red lanterns, glowing white halo above head, floating translucent blue koi fish, soft blue bokeh, misty clouds around chest,\n"
                + "lighting: cinematic cool blue tone, rim light on character, glowing hands/accessories, window backlight, high contrast, ethereal dreamy atmosphere,\n"
                + "composition: medium shot, eye contact, gentle smile, shallow depth of field, sharp focus on character,\n"
                + "style: fantasy, ethereal, cyberpunk oriental fusion, photorealistic, rich texture, high saturation blue grading,\n"
                + "negative prompt: low quality, blurry, ugly, deformed, incomplete, extra limbs, bad anatomy, text, watermark, signature, nsfw";
    }

    private static String getPrompt2() {
        return "【角色描述】\n"
                + "保持人物原型完全不变：\n\n"
                + "【场景设定】\n"
                + "末世风雪工业废墟场景：\n"
                + "- 人物身旁是覆雪的黑色雪地摩托（带黄色装饰条纹）；\n"
                + "- 背景是落雪的枯树、模糊的工业建筑轮廓，空中飘着雪花与薄雾，地面与物体表面覆盖薄雪；\n"
                + "- 环境元素：集装箱、货运木箱（印有模糊标识）、金属管道，整体是极寒末世的萧瑟感。\n\n"
                + "【光影与氛围】\n"
                + "低角度侧逆光+局部暖光：\n"
                + "- 冷调侧逆光从左后方打光，在人物发丝、外套边缘形成明亮轮廓光，人物正面用暖黄补光打亮面部与衣物；\n"
                + "- 背景为冷灰调，暗部保留细腻阴影，雪花在光线下形成丁达尔效应，营造出孤独、坚韧的末世生存氛围；\n"
                + "- 整体色调偏冷，人物与局部光源形成冷暖对比，氛围感伤感又充满力量。\n\n"
                + "【画质要求】\n"
                + "8K超写实摄影，电影级构图，低角度仰拍，广角镜头；\n"
                + "细腻材质纹理：雪地的颗粒感；\n"
                + "景深效果：背景适度虚化，突出人物主体，雪花与雾气自然融入画面，无抠图感；\n"
                + "动态效果：轻微运动模糊，强化风雪的流动感。";
    }

    private static String getPrompt3() {
        return "【角色描述】保持人物原型完全不变，全身/半身构图\n\n"
                + "【场景设定】\n"
                + "站在末世工业废墟的破碎地面上，脚下是尖锐透明的冰棱、碎石与断裂的钢筋，地面残留薄雪与水渍，冰面反射冷光；\n"
                + "背景是冒着淡淡白烟的巨型钢铁工厂建筑群，锈迹斑斑的管道纵横交错，远处烟囱与机械结构在阴云下若隐若现，围栏与警示锥散落其间，植被在废墟缝隙中顽强生长。\n\n"
                + "【光影与氛围】\n"
                + "**明确侧光（左侧/右侧硬侧光）**：\n"
                + "冷调侧逆光从人物侧后方打光，在发丝、飘带与裙摆边缘形成金色轮廓光，人物正面形成柔和的明暗过渡，暗部保留细腻阴影细节；\n"
                + "空中漂浮细碎冰晶与雪花，光线穿过冰晶形成丁达尔效应，整体色调偏冷灰，营造出孤独、坚韧又充满希望的末世氛围，伤感却有力量。\n\n"
                + "【画质要求】\n"
                + "8K超写实摄影，电影级构图，人物居中，全身/半身构图；\n"
                + "细腻材质纹理：布料的飘逸褶皱、金属配饰的光泽、冰面的通透反光、皮肤的细腻质感；\n"
                + "景深效果：背景虚化，突出人物主体，环境光自然融入人物，无抠图感，氛围感拉满。";
    }
}
