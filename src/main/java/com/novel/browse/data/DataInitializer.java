package com.novel.browse.data;

import com.novel.browse.domain.Category;
import com.novel.browse.domain.Chapter;
import com.novel.browse.domain.Novel;
import com.novel.browse.repository.CategoryRepository;
import com.novel.browse.repository.ChapterRepository;
import com.novel.browse.repository.NovelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Site initialization: seeds demo categories, novels and chapter content when the database is empty.
 */
@Component
@Order(10)   // Seeds demo data first, then EpubAutoLoader (Order 100) scans the upload directory
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final CategoryRepository categoryRepository;
    private final NovelRepository novelRepository;
    private final ChapterRepository chapterRepository;

    public DataInitializer(CategoryRepository categoryRepository,
                           NovelRepository novelRepository,
                           ChapterRepository chapterRepository) {
        this.categoryRepository = categoryRepository;
        this.novelRepository = novelRepository;
        this.chapterRepository = chapterRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (novelRepository.count() > 0) {
            log.info("{} novel(s) already exist, skipping demo data initialization", novelRepository.count());
            return;
        }
        long start = System.currentTimeMillis();
        Map<String, Category> categories = seedCategories();
        List<NovelSeed> seeds = novelSeeds();
        int total = 0;
        for (NovelSeed seed : seeds) {
            Novel novel = saveNovel(seed, categories.get(seed.category));
            saveChapters(novel, seed);
            total += novel.getChapterCount();
        }
        log.info("Demo data initialized: {} categories, {} novels, {} chapters, took {} ms",
                categories.size(), seeds.size(), total, System.currentTimeMillis() - start);
    }

    private Map<String, Category> seedCategories() {
        LinkedHashMap<String, Category> map = new LinkedHashMap<>();
        String[][] defs = {
                {"玄幻奇幻", "御剑乘风，问长生何处", "1"},
                {"都市职场", "霓虹之下，亦有热血与柔软", "2"},
                {"悬疑推理", "真相往往藏在最不起眼的细节里", "3"},
                {"历史军事", "铁马冰河，写一段家国春秋", "4"},
                {"科幻末世", "星辰与废墟，皆是人类来处与归途", "5"},
                {"言情青春", "那年夏天的风，吹过整个青春", "6"},
        };
        for (String[] def : defs) {
            Category c = categoryRepository.save(new Category(def[0], def[1], Integer.valueOf(def[2])));
            map.put(def[0], c);
        }
        return map;
    }

    private Novel saveNovel(NovelSeed seed, Category category) {
        Novel novel = new Novel();
        novel.setTitle(seed.title);
        novel.setAuthor(seed.author);
        novel.setCategory(category);
        novel.setCoverColor(seed.coverColor);
        novel.setCoverMark(seed.coverMark);
        novel.setCoverImage(seed.coverImage);
        novel.setDescription(seed.description);
        novel.setTags(String.join(",", seed.tags));
        novel.setStatus(seed.status);
        novel.setViewCount(seed.views);
        novel.setRating(seed.rating);
        novel.setChapterCount(seed.chapters.length);
        long words = 0L;
        for (int i = 0; i < seed.chapters.length; i++) {
            words += wordCount(buildContent(seed, i + 1, seed.chapters[i]));
        }
        novel.setWordCount(words);
        LocalDateTime now = LocalDateTime.now();
        novel.setCreatedAt(now.minusDays(seed.daysAgo));
        novel.setUpdatedAt(now.minusDays(seed.daysAgo).plusHours(seed.lastUpdateHoursAgo));
        Novel saved = novelRepository.save(novel);
        return saved;
    }

    private void saveChapters(Novel novel, NovelSeed seed) {
        List<Chapter> chapters = new ArrayList<>(seed.chapters.length);
        LocalDateTime createdAt = novel.getCreatedAt();
        for (int i = 0; i < seed.chapters.length; i++) {
            String content = buildContent(seed, i + 1, seed.chapters[i]);
            Chapter chapter = new Chapter();
            chapter.setNovel(novel);
            chapter.setChapterNo(i + 1);
            chapter.setTitle(seed.chapters[i]);
            chapter.setContent(content);
            chapter.setWordCount((int) wordCount(content));
            chapter.setCreatedAt(createdAt.plusHours((long) i * seed.intervalHours));
            chapters.add(chapter);
        }
        chapterRepository.saveAll(chapters);
    }

    /** Chapter content: rotates through the paragraph pool of the genre so adjacent chapters differ */
    private String buildContent(NovelSeed seed, int chapterNo, String chapterTitle) {
        String[] pool = PARAGRAPHS.get(seed.bodyType);
        StringBuilder sb = new StringBuilder();
        sb.append(chapterTitle).append('\n');
        int start = (chapterNo - 1) * seed.step;
        for (int i = 0; i < seed.paragraphPerChapter; i++) {
            int idx = (start + i) % pool.length;
            String p = pool[idx];
            if (chapterNo > 1 && i == 0) {
                p = chapterIntro(chapterNo) + p;
            }
            sb.append('\n').append(p).append('\n');
        }
        return sb.toString();
    }

    private String chapterIntro(int chapterNo) {
        String[] intros = {
                "上回说到此处，风声忽紧。",
                "故事还得从那天清晨说起。",
                "夜色未散，局势却已悄然变了。",
                "话分两头，且按下慢表。",
                "这一个新的清晨，来得出乎意料。",
        };
        return intros[chapterNo % intros.length];
    }

    private long wordCount(String text) {
        if (text == null) {
            return 0L;
        }
        long count = 0L;
        for (char c : text.toCharArray()) {
            if (!Character.isWhitespace(c)) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // Demo data definition (Chinese content, kept as-is)
    // ------------------------------------------------------------------

    private record NovelSeed(String title, String author, String category, String coverColor, String coverMark,
                             String coverImage, String description, String status, long views, double rating, long daysAgo,
                             int lastUpdateHoursAgo, int intervalHours, int step, int paragraphPerChapter,
                             String[] tags, String[] chapters, String bodyType) {
    }

    private static final Map<String, String[]> PARAGRAPHS = Map.of(
            "玄幻奇幻", new String[]{
                    "青石阶上一层薄霜，少年提着那柄缺口的长剑，一步步走上山门。风从峡谷深处灌来，吹得他衣袍猎猎，也吹散了旁人眼底的轻蔑。",
                    "灵气自丹田升起，如一线细泉汇入江河。他盘坐在寒潭边，任凭夜露沾湿眉睫，心神沉入那片看不见底的黑暗。",
                    "老仆把最后一袋干粮塞进他怀里，只说了一句：活着回来。山道漫长，这句话却比干粮更耐嚼。",
                    "剑意将成未成之际，天地间没有声响。他忽然明白，所谓大道，不过是把每一件小事做到极致。",
                    "苍梧山脉十二月落雪，雪下埋着前朝剑冢。他循着碑文拓走去，指腹抚过那些被风磨平的名字。",
                    "魔道众人围上来时，他笑了一声。笑得毫无把握，却让对面迟疑了半息——半息，足够决胜负。",
                    "炉火熄灭前的最后一刻，丹成。他听见自己心跳如雷，也听见师父在门外长长呼出一口气。",
                    "天劫云压城而来，雷光在云腹里翻滚。没有人替他挡，也不必有人替他挡。",
                    "他终于握住了那把剑的剑柄，才发现剑比传言里更轻。轻得像一句承诺。",
                    "多年以后有人问他，当年为何不回头。他想了想，说，怕看见那一山的灯火会舍不得。",
            },
            "都市职场", new String[]{
                    "早高峰的地铁把人挤成薄纸，他靠在门边，把最后一版方案又读了一遍，然后按下发送。",
                    "会议室里冷气很足，总监翻着材料的动作很慢。他知道，慢意味着不满意。",
                    "凌晨两点的写字楼还有几盏灯亮着。她把泡面汤喝完，纸巾擦了擦键盘上的油渍，继续敲。",
                    "客户在电话那头笑得很客气，话却很硬。他掐了掐眉心，回了句：我明白您的意思。",
                    "年终述职那天，他从 PPT 第一页讲到最后一页，忽然觉得这一年好像只过了一个月。",
                    "楼下咖啡店的姑娘记得他的口味，这让他在这座两千万人的城市里，第一次有了点归属感。",
                    "团队散伙饭吃到最后，大家都没提下家的事。有些告别不需要说明。",
                    "他被调去负责一个新项目，预算只有对手的一半。他在白板上写下第一行字：先弄明白用户到底要什么。",
                    "雨夜打车难，他和同事撑一把伞走到路口。伞很小，肩膀湿了一半，话却比平时多。",
                    "签下那笔单子的晚上，他没有庆祝，只是在工位上多坐了十分钟，看着窗外亮起来的广告牌。",
            },
            "悬疑推理", new String[]{
                    "门锁没有撬痕，窗户从内锁死。她戴上手套，蹲下身，地砖缝里有一小片干涸的水渍。",
                    "监控录像停在二十三点四十七分。之后的三分钟什么也看不见，这才是关键。",
                    "报案人说话时右手一直摩挲左手腕。这个动作比他那番话更值得记录。",
                    "雨声掩盖了枪响，也掩盖了脚印。勘查灯打过去，泥地里只有一串向外的足印。",
                    "他把三份口供摊在桌上，发现三个人都提到了同一件小事，却给出了三种说法。",
                    "地下室的味道刺鼻，墙角的石灰是新抹上去的。指甲刮过，露出一点暗红。",
                    "案发当天那趟末班车，只有四个人下车。监控却拍到了五个影子。",
                    "她翻旧档案到第三十七页时，看见了那枚相同的指纹，日期是十二年前。",
                    "嫌疑人笑了笑，说：你们查得很仔细。这种时候的夸奖，往往不是好消息。",
                    "真相揭开时并不惊天动地，只是让人心里发凉——因为一切都太合乎逻辑了。",
            },
            "历史军事", new String[]{
                    "边关的雪下了三天，斥候的马倒在城门口，背上插着两支羽箭，人还剩一口气。",
                    "将军立在城头，看着远处连成片的营火。他知道，这一仗避不开了。",
                    "粮车上路那天，百姓沿街跪送。他掀开帘子看了很久，什么也没说。",
                    "朝堂上的争论从午后持续到掌灯。最终皇上的一句“缓议”，把前线又推远了三百里。",
                    "新兵第一次上阵，握枪的手抖得厉害。老兵拍了拍他的背：跟紧我。",
                    "夜袭之时不点灯笼，只听马蹄与呼吸。过了河，火把才一支支亮起来。",
                    "战后清点，战殁者名册写满了三张纸。他亲手把名字一个个念过去，念到最后一个时天已亮。",
                    "文书从京城八百里加急送来，信封边口沾着泥。他拆开，脸色没变，手却收得很紧。",
                    "城墙补了又塌，塌了又补。有人在砖上刻下自己的名字，说是来年要回家。",
                    "凯旋那天城里人山人海，他却先去了城外的乱葬岗，站了一炷香的工夫。",
            },
            "科幻末世", new String[]{
                    "检测屏上的辐射值又跳了一格。他把最后一块电池塞进背包，拉紧面罩。",
                    "飞船掠过废弃空间站，金属外壳在恒星风里发出低低的哀鸣。",
                    "穹顶之外的天空永远是灰的。孩子们只在课本上见过蓝色，但他们相信那就是海的颜色。",
                    "第三次醒来时，他的记忆区块被删掉了七年——医生说是为了让他活下去。",
                    "探测车在沙丘背面停住，雷达显示出规则的几何轮廓。那不可能是自然形成的。",
                    "她把仅剩的氧气阀值调到最低，在黑暗里算了一遍又一遍，直到确信可以回得来。",
                    "旧世界的服务器还在运转，只是再没有人问它问题。屏幕忽明忽暗，像在努力想起什么。",
                    "他把种子收入舱内，忽然理解了为什么古人要把最重要的东西称作“希望”。",
                    "轨道上的城市亮着一圈冷光，像戴了一枚戒指。穷人住在戒指的阴影里。",
                    "信号天后衰落之前，那句话被重复了三十七遍：如果你们还在，请回答。",
            },
            "言情青春", new String[]{
                    "教室后排的风扇吱呀转着，他把练习册推过来，压低声音问了最后一道题。",
                    "她撑着伞站在站台边，伞面朝外倾斜了半寸——刚好够两个人都不淋湿。",
                    "毕业合影那天阳光刺眼，所有人喊“茄子”，只有他看向镜头外。",
                    "她把那张纸条夹进了书的第九十七页，很多年后再翻开，字迹还像新的一样。",
                    "夏天的晚风里有汽水的味道，还有她说不清楚的那种心跳。",
                    "他记得她所有的喜好，唯独没记住她怕黑——后来每次走夜路，他都走在外侧。",
                    "大学录取通知书到的那天，两个城市之间的距离忽然变得具体起来。",
                    "再相遇是在朋友的婚礼上。她笑着问：好久不见。他说：不见很久了。",
                    "便利店关东煮的热气模糊了玻璃，他的手在口袋里握了一路的车票。",
                    "很多年后他们才明白，青春不是某段日子，而是某个人在时的那种天气。",
            }
    );

    private List<NovelSeed> novelSeeds() {
        List<NovelSeed> seeds = new ArrayList<>();
        seeds.add(new NovelSeed(
                "登云帖",
                "青冥",
                "玄幻奇幻",
                "linear-gradient(135deg,#3b82f6,#7c3aed)", "剑", "demo-dengyuntie.jpg",
                "一个没有灵根的少年，带着一张写错名字的登云帖，叩开了天下第一剑宗的山门。\n" +
                        "他没有天赋，只有比别人更长的夜。当所有人都在问他凭什么时，他已经在雪里站了整整三年。",
                Novel.STATUS_ONGOING, 1892650L, 9.2, 120, 6, 26, 3, 5,
                new String[]{"热血", "成长", "剑修", "逆袭"},
                new String[]{"第一章 山门前的雪", "第二章 没有灵根的人", "第三章 扫雪三年", "第四章 第一缕灵气",
                        "第五章 同门", "第六章 剑冢", "第七章 论剑台上", "第八章 一剑破尘",
                        "第九章 下山", "第十章 苍梧夜战", "第十一章 旧友", "第十二章 丹成",
                        "第十三章 天劫将至", "第十四章 独闯云海", "第十五章 故人之约", "第十六章 登云"},
                "玄幻奇幻"));
        seeds.add(new NovelSeed(
                "长夜拾星",
                "苏砚",
                "玄幻奇幻",
                "linear-gradient(135deg,#0ea5e9,#1e3a8a)", "星", "demo-changyeshixing.jpg",
                "星落之夜，被人视为灾星的少女被逐出家门。她于长夜里拾起第一颗星，从此命盘不再属于天道。",
                Novel.STATUS_FINISHED, 986780L, 8.8, 260, 240, 30, 4, 5,
                new String[]{"女主", "逆袭", "星象", "古典"},
                new String[]{"第一章 灾星出生", "第二章 逐出家门", "第三章 荒庙", "第四章 拾星",
                        "第五章 命理之外", "第六章 故城", "第七章 观星台", "第八章 旧事重提",
                        "第九章 破命", "第十章 归途", "第十一章 与天争", "第十二章 长夜尽处"},
                "玄幻奇幻"));
        seeds.add(new NovelSeed(
                "半城灯火",
                "林小满",
                "都市职场",
                "linear-gradient(135deg,#f97316,#ef4444)", "城", "demo-banchengdenghuo.jpg",
                "三十二岁的他被优化那天，给楼下便利店的猫买了根火腿肠。人生从那一刻重新开始。\n" +
                        "没有金手指，只有一个死也不肯放弃的项目。",
                Novel.STATUS_ONGOING, 2145870L, 9.4, 90, 3, 24, 2, 5,
                new String[]{"职场", "创业", "现实", "治愈"},
                new String[]{"第一章 被优化的那天", "第二章 最后一个工位", "第三章 三个人",
                        "第四章 预算只有一半", "第五章 第一次碰壁", "第六章 雨夜同行",
                        "第七章 用户到底要什么", "第八章 转机", "第九章 谈判桌上",
                        "第十章 半年以后", "第十一章 抉择", "第十二章 灯火半城",
                        "第十三章 新的开始", "第十四章 又一次早高峰"},
                "都市职场"));
        seeds.add(new NovelSeed(
                "投行夜未眠",
                "何夜",
                "都市职场",
                "linear-gradient(135deg,#14b8a6,#0f766e)", "金", "demo-touhangyebumian.jpg",
                "她是所有人眼里最快的项目经理，习惯在天亮前关灯。直到有一天，有人把她的灯留到天亮。",
                Novel.STATUS_FINISHED, 1342650L, 8.9, 300, 480, 36, 3, 5,
                new String[]{"金融", "职场", "情感", "都市"},
                new String[]{"第一章 凌晨两点", "第二章 冷气很足的会议室", "第三章 客气的电话",
                        "第四章 归属感的重量", "第五章 述职日", "第六章 散伙饭",
                        "第七章 一半的预算", "第八章 湿掉的肩膀", "第九章 路灯下",
                        "第十章 签字的夜晚", "第十一章 留一盏灯"},
                "都市职场"));
        seeds.add(new NovelSeed(
                "沉默的第三人",
                "程默",
                "悬疑推理",
                "linear-gradient(135deg,#334155,#111827)", "案", "demo-chenmodisanren.jpg",
                "密室、消失的三分钟、五种口供。刑警队长沈知远在旧档案里翻出十二年前的一枚指纹——那是他自己的。",
                Novel.STATUS_ONGOING, 3245870L, 9.6, 45, 2, 20, 1, 5,
                new String[]{"刑侦", "密室", "反转", "硬核"},
                new String[]{"第一章 内锁的窗", "第二章 消失的三分钟", "第三章 摩挲手腕的人",
                        "第四章 雨夜勘查", "第五章 三种说法", "第六章 新抹的石灰",
                        "第七章 第五个影子", "第八章 十二年前的指纹", "第九章 你查得很仔细",
                        "第十章 合乎逻辑", "第十一章 沉默的第三人", "第十二章 余案未了"},
                "悬疑推理"));
        seeds.add(new NovelSeed(
                "铁衣渡关",
                "北望",
                "历史军事",
                "linear-gradient(135deg,#b45309,#78350f)", "甲", "demo-tieyiduguan.jpg",
                "边境小镇失守的第七日，被夺去兵权的老将重新披甲。他手里只剩三百人和一封没送到的诏书。",
                Novel.STATUS_ONGOING, 876540L, 9.0, 70, 8, 28, 3, 5,
                new String[]{"战争", "家国", "老将", "悲壮"},
                new String[]{"第一章 关下的雪", "第二章 三百人", "第三章 百姓沿街跪送",
                        "第四章 朝堂的缓议", "第五章 新兵上阵", "第六章 夜不点火",
                        "第七章 念这三个名字", "第八章 八百里加急", "第九章 砖上的名字",
                        "第十章 城头一战", "第十一章 凯旋之后"},
                "历史军事"));
        seeds.add(new NovelSeed(
                "信号三十七",
                "远星",
                "科幻末世",
                "linear-gradient(135deg,#6366f1,#4338ca)", "穹", "demo-xinhaosanshiqi.jpg",
                "穹顶坍塌后的第九年，拾荒者邦州在辐射带捡到一台仍会说话的旧设备。它重复了三十七遍同一句话。",
                Novel.STATUS_ONGOING, 1465800L, 9.1, 100, 5, 30, 2, 5,
                new String[]{"末世", "太空", "希望", "硬科幻"},
                new String[]{"第一章 辐射值又跳了一格", "第二章 废弃空间站", "第三章 课本上的蓝",
                        "第四章 被删除的七年", "第五章 沙丘背面", "第六章 最低氧耗",
                        "第七章 还在运转的服务器", "第八章 种子的重量", "第九章 戒指的阴影",
                        "第十章 三十七遍", "第十一章 如果你们还在", "第十二章 回答"},
                "科幻末世"));
        seeds.add(new NovelSeed(
                "第九十七页",
                "陈也",
                "言情青春",
                "linear-gradient(135deg,#ec4899,#f472b6)", "夏", "demo-dijiushiyiye.jpg",
                "高三那年的夏天，他把所有想说的话写在一张纸条上，夹进了她的书里——第九十七页。\n" +
                        "十年后同学聚会，那本书又被搬了出来。",
                Novel.STATUS_FINISHED, 654320L, 8.6, 200, 600, 40, 3, 5,
                new String[]{"青春", "暗恋", "重逢", "温暖"},
                new String[]{"第一章 后排的电风扇", "第二章 倾斜的伞", "第三章 毕业合影",
                        "第四章 第九十七页", "第五章 汽水味的晚风", "第六章 忘了她怕黑",
                        "第七章 两个城市", "第八章 好久不见", "第九章 关东煮的热气",
                        "第十章 握了一路的车票", "第十一章 她在时的天气"},
                "言情青春"));
        return seeds;
    }
}
