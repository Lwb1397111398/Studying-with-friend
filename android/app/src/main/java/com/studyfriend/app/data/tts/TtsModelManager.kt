package com.studyfriend.app.data.tts

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 离线朗读模型（kokoro-int8-multi-lang-v1_1，中英双语）下载/管理。
 * 经验来自「听的思维」项目同款清单 + 本项目 M7 更新下载器（.part 断点续传 + 200 自愈）：
 * - 源站 hf-mirror.com（HuggingFace 国内镜像），直连可达；
 * - 单文件 .part 续传：中断后再点从已下字节继续（Range 头），服务端不认 Range 回 200 则弃 .part 重下；
 * - 下载挂在本 object 的 App 级 scope：离开设置页不中断，回页继续看进度。
 * 字节数兼作完整性下限：下载完尺寸不符报错、整文件作废。
 */
object TtsModelManager {

    private const val TTS_REPO =
        "https://hf-mirror.com/csukuangfj/kokoro-int8-multi-lang-v1_1/resolve/main"

    /** 一个待下载文件：仓库内相对路径（可能带子目录）+ 预期字节数（完整性下限） */
    data class TtsFile(val relPath: String, val bytes: Long)

    // 清单即仓库实况（听的思维 2026-09 核对）：大文件在前、espeak 语音数据垫底
    val TTS_FILES: List<TtsFile> = listOf(
        TtsFile("model.int8.onnx", 114299010L),
        TtsFile("voices.bin", 53790720L),
        TtsFile("lexicon-zh.txt", 2119465L),
        TtsFile("lexicon-us-en.txt", 5956885L),
        TtsFile("lexicon-gb-en.txt", 6366635L),
        TtsFile("date-zh.fst", 59154L),
        TtsFile("number-zh.fst", 64482L),
        TtsFile("phone-zh.fst", 88630L),
        TtsFile("dict/hmm_model.utf8", 519739L),
        TtsFile("dict/idf.utf8", 5998717L),
        TtsFile("dict/jieba.dict.utf8", 5071204L),
        TtsFile("dict/pos_dict/char_state_tab.utf8", 327139L),
        TtsFile("dict/pos_dict/prob_emit.utf8", 1687686L),
        TtsFile("dict/pos_dict/prob_start.utf8", 4347L),
        TtsFile("dict/pos_dict/prob_trans.utf8", 124159L),
        TtsFile("dict/stop_words.utf8", 8974L),
        TtsFile("dict/user.dict.utf8", 829194L),
        TtsFile("tokens.txt", 1111L),
        TtsFile("espeak-ng-data/af_dict", 121473L),
        TtsFile("espeak-ng-data/am_dict", 63878L),
        TtsFile("espeak-ng-data/an_dict", 6691L),
        TtsFile("espeak-ng-data/ar_dict", 478165L),
        TtsFile("espeak-ng-data/as_dict", 5005L),
        TtsFile("espeak-ng-data/az_dict", 43773L),
        TtsFile("espeak-ng-data/ba_dict", 2098L),
        TtsFile("espeak-ng-data/be_dict", 2652L),
        TtsFile("espeak-ng-data/bg_dict", 87051L),
        TtsFile("espeak-ng-data/bn_dict", 89979L),
        TtsFile("espeak-ng-data/bpy_dict", 5226L),
        TtsFile("espeak-ng-data/bs_dict", 47068L),
        TtsFile("espeak-ng-data/ca_dict", 45566L),
        TtsFile("espeak-ng-data/chr_dict", 2859L),
        TtsFile("espeak-ng-data/cmn_dict", 1566335L),
        TtsFile("espeak-ng-data/cs_dict", 49645L),
        TtsFile("espeak-ng-data/cv_dict", 1344L),
        TtsFile("espeak-ng-data/cy_dict", 43130L),
        TtsFile("espeak-ng-data/da_dict", 245287L),
        TtsFile("espeak-ng-data/de_dict", 68276L),
        TtsFile("espeak-ng-data/el_dict", 72841L),
        TtsFile("espeak-ng-data/en_dict", 166944L),
        TtsFile("espeak-ng-data/eo_dict", 4666L),
        TtsFile("espeak-ng-data/es_dict", 49252L),
        TtsFile("espeak-ng-data/et_dict", 44263L),
        TtsFile("espeak-ng-data/eu_dict", 48841L),
        TtsFile("espeak-ng-data/fa_dict", 292423L),
        TtsFile("espeak-ng-data/fi_dict", 43928L),
        TtsFile("espeak-ng-data/fr_dict", 63727L),
        TtsFile("espeak-ng-data/ga_dict", 52673L),
        TtsFile("espeak-ng-data/gd_dict", 49121L),
        TtsFile("espeak-ng-data/gn_dict", 3248L),
        TtsFile("espeak-ng-data/grc_dict", 3433L),
        TtsFile("espeak-ng-data/gu_dict", 82480L),
        TtsFile("espeak-ng-data/hak_dict", 3335L),
        TtsFile("espeak-ng-data/haw_dict", 2443L),
        TtsFile("espeak-ng-data/he_dict", 6963L),
        TtsFile("espeak-ng-data/hi_dict", 92143L),
        TtsFile("espeak-ng-data/hr_dict", 49388L),
        TtsFile("espeak-ng-data/ht_dict", 1803L),
        TtsFile("espeak-ng-data/hu_dict", 153785L),
        TtsFile("espeak-ng-data/hy_dict", 62263L),
        TtsFile("espeak-ng-data/ia_dict", 331275L),
        TtsFile("espeak-ng-data/id_dict", 43458L),
        TtsFile("espeak-ng-data/intonations", 2040L),
        TtsFile("espeak-ng-data/io_dict", 2165L),
        TtsFile("espeak-ng-data/is_dict", 44354L),
        TtsFile("espeak-ng-data/it_dict", 152889L),
        TtsFile("espeak-ng-data/ja_dict", 47652L),
        TtsFile("espeak-ng-data/jbo_dict", 2243L),
        TtsFile("espeak-ng-data/ka_dict", 87775L),
        TtsFile("espeak-ng-data/kk_dict", 1859L),
        TtsFile("espeak-ng-data/kl_dict", 2838L),
        TtsFile("espeak-ng-data/kn_dict", 87828L),
        TtsFile("espeak-ng-data/ko_dict", 47523L),
        TtsFile("espeak-ng-data/kok_dict", 6394L),
        TtsFile("espeak-ng-data/ku_dict", 2265L),
        TtsFile("espeak-ng-data/ky_dict", 64977L),
        TtsFile("espeak-ng-data/la_dict", 3806L),
        TtsFile("espeak-ng-data/lang/aav/vi", 111L),
        TtsFile("espeak-ng-data/lang/aav/vi-VN-x-central", 143L),
        TtsFile("espeak-ng-data/lang/aav/vi-VN-x-south", 142L),
        TtsFile("espeak-ng-data/lang/art/eo", 41L),
        TtsFile("espeak-ng-data/lang/art/ia", 29L),
        TtsFile("espeak-ng-data/lang/art/io", 50L),
        TtsFile("espeak-ng-data/lang/art/jbo", 69L),
        TtsFile("espeak-ng-data/lang/art/lfn", 135L),
        TtsFile("espeak-ng-data/lang/art/piqd", 56L),
        TtsFile("espeak-ng-data/lang/art/py", 140L),
        TtsFile("espeak-ng-data/lang/art/qdb", 57L),
        TtsFile("espeak-ng-data/lang/art/qya", 173L),
        TtsFile("espeak-ng-data/lang/art/sjn", 175L),
        TtsFile("espeak-ng-data/lang/azc/nci", 114L),
        TtsFile("espeak-ng-data/lang/bat/lt", 28L),
        TtsFile("espeak-ng-data/lang/bat/ltg", 312L),
        TtsFile("espeak-ng-data/lang/bat/lv", 229L),
        TtsFile("espeak-ng-data/lang/bnt/sw", 41L),
        TtsFile("espeak-ng-data/lang/bnt/tn", 42L),
        TtsFile("espeak-ng-data/lang/ccs/ka", 124L),
        TtsFile("espeak-ng-data/lang/cel/cy", 37L),
        TtsFile("espeak-ng-data/lang/cel/ga", 66L),
        TtsFile("espeak-ng-data/lang/cel/gd", 51L),
        TtsFile("espeak-ng-data/lang/cus/om", 39L),
        TtsFile("espeak-ng-data/lang/dra/kn", 55L),
        TtsFile("espeak-ng-data/lang/dra/ml", 57L),
        TtsFile("espeak-ng-data/lang/dra/ta", 51L),
        TtsFile("espeak-ng-data/lang/dra/te", 70L),
        TtsFile("espeak-ng-data/lang/esx/kl", 30L),
        TtsFile("espeak-ng-data/lang/eu", 54L),
        TtsFile("espeak-ng-data/lang/gmq/da", 43L),
        TtsFile("espeak-ng-data/lang/gmq/is", 27L),
        TtsFile("espeak-ng-data/lang/gmq/nb", 87L),
        TtsFile("espeak-ng-data/lang/gmq/sv", 25L),
        TtsFile("espeak-ng-data/lang/gmw/af", 123L),
        TtsFile("espeak-ng-data/lang/gmw/de", 42L),
        TtsFile("espeak-ng-data/lang/gmw/en", 140L),
        TtsFile("espeak-ng-data/lang/gmw/en-029", 335L),
        TtsFile("espeak-ng-data/lang/gmw/en-GB-scotland", 295L),
        TtsFile("espeak-ng-data/lang/gmw/en-GB-x-gbclan", 238L),
        TtsFile("espeak-ng-data/lang/gmw/en-GB-x-gbcwmd", 188L),
        TtsFile("espeak-ng-data/lang/gmw/en-GB-x-rp", 249L),
        TtsFile("espeak-ng-data/lang/gmw/en-US", 257L),
        TtsFile("espeak-ng-data/lang/gmw/en-US-nyc", 271L),
        TtsFile("espeak-ng-data/lang/gmw/lb", 31L),
        TtsFile("espeak-ng-data/lang/gmw/nl", 23L),
        TtsFile("espeak-ng-data/lang/grk/el", 23L),
        TtsFile("espeak-ng-data/lang/grk/grc", 99L),
        TtsFile("espeak-ng-data/lang/inc/as", 42L),
        TtsFile("espeak-ng-data/lang/inc/bn", 25L),
        TtsFile("espeak-ng-data/lang/inc/bpy", 39L),
        TtsFile("espeak-ng-data/lang/inc/gu", 42L),
        TtsFile("espeak-ng-data/lang/inc/hi", 23L),
        TtsFile("espeak-ng-data/lang/inc/kok", 26L),
        TtsFile("espeak-ng-data/lang/inc/mr", 41L),
        TtsFile("espeak-ng-data/lang/inc/ne", 37L),
        TtsFile("espeak-ng-data/lang/inc/or", 39L),
        TtsFile("espeak-ng-data/lang/inc/pa", 25L),
        TtsFile("espeak-ng-data/lang/inc/sd", 66L),
        TtsFile("espeak-ng-data/lang/inc/si", 55L),
        TtsFile("espeak-ng-data/lang/inc/ur", 94L),
        TtsFile("espeak-ng-data/lang/ine/hy", 61L),
        TtsFile("espeak-ng-data/lang/ine/hyw", 365L),
        TtsFile("espeak-ng-data/lang/ine/sq", 103L),
        TtsFile("espeak-ng-data/lang/ira/fa", 90L),
        TtsFile("espeak-ng-data/lang/ira/fa-Latn", 269L),
        TtsFile("espeak-ng-data/lang/ira/ku", 40L),
        TtsFile("espeak-ng-data/lang/iro/chr", 569L),
        TtsFile("espeak-ng-data/lang/itc/la", 297L),
        TtsFile("espeak-ng-data/lang/jpx/ja", 52L),
        TtsFile("espeak-ng-data/lang/ko", 51L),
        TtsFile("espeak-ng-data/lang/map/haw", 42L),
        TtsFile("espeak-ng-data/lang/miz/mto", 183L),
        TtsFile("espeak-ng-data/lang/myn/quc", 210L),
        TtsFile("espeak-ng-data/lang/poz/id", 134L),
        TtsFile("espeak-ng-data/lang/poz/mi", 367L),
        TtsFile("espeak-ng-data/lang/poz/ms", 430L),
        TtsFile("espeak-ng-data/lang/qu", 88L),
        TtsFile("espeak-ng-data/lang/roa/an", 27L),
        TtsFile("espeak-ng-data/lang/roa/ca", 25L),
        TtsFile("espeak-ng-data/lang/roa/es", 63L),
        TtsFile("espeak-ng-data/lang/roa/es-419", 167L),
        TtsFile("espeak-ng-data/lang/roa/fr", 79L),
        TtsFile("espeak-ng-data/lang/roa/fr-BE", 84L),
        TtsFile("espeak-ng-data/lang/roa/fr-CH", 86L),
        TtsFile("espeak-ng-data/lang/roa/ht", 140L),
        TtsFile("espeak-ng-data/lang/roa/it", 109L),
        TtsFile("espeak-ng-data/lang/roa/pap", 62L),
        TtsFile("espeak-ng-data/lang/roa/pt", 95L),
        TtsFile("espeak-ng-data/lang/roa/pt-BR", 109L),
        TtsFile("espeak-ng-data/lang/roa/ro", 26L),
        TtsFile("espeak-ng-data/lang/sai/gn", 47L),
        TtsFile("espeak-ng-data/lang/sem/am", 41L),
        TtsFile("espeak-ng-data/lang/sem/ar", 50L),
        TtsFile("espeak-ng-data/lang/sem/he", 40L),
        TtsFile("espeak-ng-data/lang/sem/mt", 41L),
        TtsFile("espeak-ng-data/lang/sit/cmn", 686L),
        TtsFile("espeak-ng-data/lang/sit/cmn-Latn-pinyin", 161L),
        TtsFile("espeak-ng-data/lang/sit/hak", 128L),
        TtsFile("espeak-ng-data/lang/sit/my", 56L),
        TtsFile("espeak-ng-data/lang/sit/yue", 194L),
        TtsFile("espeak-ng-data/lang/sit/yue-Latn-jyutping", 213L),
        TtsFile("espeak-ng-data/lang/tai/shn", 92L),
        TtsFile("espeak-ng-data/lang/tai/th", 37L),
        TtsFile("espeak-ng-data/lang/trk/az", 45L),
        TtsFile("espeak-ng-data/lang/trk/ba", 25L),
        TtsFile("espeak-ng-data/lang/trk/cv", 40L),
        TtsFile("espeak-ng-data/lang/trk/kk", 40L),
        TtsFile("espeak-ng-data/lang/trk/ky", 43L),
        TtsFile("espeak-ng-data/lang/trk/nog", 39L),
        TtsFile("espeak-ng-data/lang/trk/tk", 25L),
        TtsFile("espeak-ng-data/lang/trk/tr", 25L),
        TtsFile("espeak-ng-data/lang/trk/tt", 23L),
        TtsFile("espeak-ng-data/lang/trk/ug", 24L),
        TtsFile("espeak-ng-data/lang/trk/uz", 39L),
        TtsFile("espeak-ng-data/lang/urj/et", 237L),
        TtsFile("espeak-ng-data/lang/urj/fi", 237L),
        TtsFile("espeak-ng-data/lang/urj/hu", 73L),
        TtsFile("espeak-ng-data/lang/urj/smj", 45L),
        TtsFile("espeak-ng-data/lang/zle/be", 52L),
        TtsFile("espeak-ng-data/lang/zle/ru", 57L),
        TtsFile("espeak-ng-data/lang/zle/ru-LV", 280L),
        TtsFile("espeak-ng-data/lang/zle/ru-cl", 91L),
        TtsFile("espeak-ng-data/lang/zle/uk", 97L),
        TtsFile("espeak-ng-data/lang/zls/bg", 111L),
        TtsFile("espeak-ng-data/lang/zls/bs", 230L),
        TtsFile("espeak-ng-data/lang/zls/hr", 262L),
        TtsFile("espeak-ng-data/lang/zls/mk", 28L),
        TtsFile("espeak-ng-data/lang/zls/sl", 43L),
        TtsFile("espeak-ng-data/lang/zls/sr", 250L),
        TtsFile("espeak-ng-data/lang/zlw/cs", 23L),
        TtsFile("espeak-ng-data/lang/zlw/pl", 38L),
        TtsFile("espeak-ng-data/lang/zlw/sk", 24L),
        TtsFile("espeak-ng-data/lb_dict", 687931L),
        TtsFile("espeak-ng-data/lfn_dict", 2793L),
        TtsFile("espeak-ng-data/lt_dict", 49890L),
        TtsFile("espeak-ng-data/lv_dict", 66337L),
        TtsFile("espeak-ng-data/mi_dict", 1346L),
        TtsFile("espeak-ng-data/mk_dict", 63859L),
        TtsFile("espeak-ng-data/ml_dict", 92345L),
        TtsFile("espeak-ng-data/mr_dict", 87391L),
        TtsFile("espeak-ng-data/ms_dict", 53541L),
        TtsFile("espeak-ng-data/mt_dict", 4384L),
        TtsFile("espeak-ng-data/mto_dict", 3960L),
        TtsFile("espeak-ng-data/my_dict", 95948L),
        TtsFile("espeak-ng-data/nci_dict", 1534L),
        TtsFile("espeak-ng-data/ne_dict", 95377L),
        TtsFile("espeak-ng-data/nl_dict", 65979L),
        TtsFile("espeak-ng-data/no_dict", 4178L),
        TtsFile("espeak-ng-data/nog_dict", 3294L),
        TtsFile("espeak-ng-data/om_dict", 2302L),
        TtsFile("espeak-ng-data/or_dict", 89246L),
        TtsFile("espeak-ng-data/pa_dict", 79953L),
        TtsFile("espeak-ng-data/pap_dict", 2128L),
        TtsFile("espeak-ng-data/phondata", 550424L),
        TtsFile("espeak-ng-data/phondata-manifest", 21821L),
        TtsFile("espeak-ng-data/phonindex", 39074L),
        TtsFile("espeak-ng-data/phontab", 55796L),
        TtsFile("espeak-ng-data/piqd_dict", 1710L),
        TtsFile("espeak-ng-data/pl_dict", 76730L),
        TtsFile("espeak-ng-data/pt_dict", 67817L),
        TtsFile("espeak-ng-data/py_dict", 2409L),
        TtsFile("espeak-ng-data/qdb_dict", 3028L),
        TtsFile("espeak-ng-data/qu_dict", 1919L),
        TtsFile("espeak-ng-data/quc_dict", 1450L),
        TtsFile("espeak-ng-data/qya_dict", 1939L),
        TtsFile("espeak-ng-data/ro_dict", 68538L),
        TtsFile("espeak-ng-data/ru_dict", 8532392L),
        TtsFile("espeak-ng-data/sd_dict", 59928L),
        TtsFile("espeak-ng-data/shn_dict", 88172L),
        TtsFile("espeak-ng-data/si_dict", 85384L),
        TtsFile("espeak-ng-data/sjn_dict", 1783L),
        TtsFile("espeak-ng-data/sk_dict", 50002L),
        TtsFile("espeak-ng-data/sl_dict", 45047L),
        TtsFile("espeak-ng-data/smj_dict", 35095L),
        TtsFile("espeak-ng-data/sq_dict", 45003L),
        TtsFile("espeak-ng-data/sr_dict", 46832L),
        TtsFile("espeak-ng-data/sv_dict", 47836L),
        TtsFile("espeak-ng-data/sw_dict", 47804L),
        TtsFile("espeak-ng-data/ta_dict", 209553L),
        TtsFile("espeak-ng-data/te_dict", 94837L),
        TtsFile("espeak-ng-data/th_dict", 2301L),
        TtsFile("espeak-ng-data/tk_dict", 20868L),
        TtsFile("espeak-ng-data/tn_dict", 3072L),
        TtsFile("espeak-ng-data/tr_dict", 46793L),
        TtsFile("espeak-ng-data/tt_dict", 2121L),
        TtsFile("espeak-ng-data/ug_dict", 2070L),
        TtsFile("espeak-ng-data/uk_dict", 3492L),
        TtsFile("espeak-ng-data/ur_dict", 133556L),
        TtsFile("espeak-ng-data/uz_dict", 2540L),
        TtsFile("espeak-ng-data/vi_dict", 52608L),
        TtsFile("espeak-ng-data/voices/!v/Alex", 128L),
        TtsFile("espeak-ng-data/voices/!v/Alicia", 474L),
        TtsFile("espeak-ng-data/voices/!v/Andrea", 357L),
        TtsFile("espeak-ng-data/voices/!v/Andy", 320L),
        TtsFile("espeak-ng-data/voices/!v/Annie", 315L),
        TtsFile("espeak-ng-data/voices/!v/AnxiousAndy", 361L),
        TtsFile("espeak-ng-data/voices/!v/Demonic", 3858L),
        TtsFile("espeak-ng-data/voices/!v/Denis", 305L),
        TtsFile("espeak-ng-data/voices/!v/Diogo", 379L),
        TtsFile("espeak-ng-data/voices/!v/Gene", 281L),
        TtsFile("espeak-ng-data/voices/!v/Gene2", 283L),
        TtsFile("espeak-ng-data/voices/!v/Henrique", 381L),
        TtsFile("espeak-ng-data/voices/!v/Hugo", 378L),
        TtsFile("espeak-ng-data/voices/!v/Jacky", 267L),
        TtsFile("espeak-ng-data/voices/!v/Lee", 338L),
        TtsFile("espeak-ng-data/voices/!v/Marco", 467L),
        TtsFile("espeak-ng-data/voices/!v/Mario", 270L),
        TtsFile("espeak-ng-data/voices/!v/Michael", 270L),
        TtsFile("espeak-ng-data/voices/!v/Mike", 112L),
        TtsFile("espeak-ng-data/voices/!v/Mr serious", 3193L),
        TtsFile("espeak-ng-data/voices/!v/Nguyen", 280L),
        TtsFile("espeak-ng-data/voices/!v/Reed", 202L),
        TtsFile("espeak-ng-data/voices/!v/RicishayMax", 233L),
        TtsFile("espeak-ng-data/voices/!v/RicishayMax2", 435L),
        TtsFile("espeak-ng-data/voices/!v/RicishayMax3", 435L),
        TtsFile("espeak-ng-data/voices/!v/Storm", 420L),
        TtsFile("espeak-ng-data/voices/!v/Tweaky", 3189L),
        TtsFile("espeak-ng-data/voices/!v/UniRobot", 417L),
        TtsFile("espeak-ng-data/voices/!v/adam", 75L),
        TtsFile("espeak-ng-data/voices/!v/anika", 493L),
        TtsFile("espeak-ng-data/voices/!v/anikaRobot", 512L),
        TtsFile("espeak-ng-data/voices/!v/announcer", 300L),
        TtsFile("espeak-ng-data/voices/!v/antonio", 381L),
        TtsFile("espeak-ng-data/voices/!v/aunty", 358L),
        TtsFile("espeak-ng-data/voices/!v/belinda", 340L),
        TtsFile("espeak-ng-data/voices/!v/benjamin", 201L),
        TtsFile("espeak-ng-data/voices/!v/boris", 224L),
        TtsFile("espeak-ng-data/voices/!v/caleb", 57L),
        TtsFile("espeak-ng-data/voices/!v/croak", 93L),
        TtsFile("espeak-ng-data/voices/!v/david", 112L),
        TtsFile("espeak-ng-data/voices/!v/ed", 287L),
        TtsFile("espeak-ng-data/voices/!v/edward", 151L),
        TtsFile("espeak-ng-data/voices/!v/edward2", 152L),
        TtsFile("espeak-ng-data/voices/!v/f1", 324L),
        TtsFile("espeak-ng-data/voices/!v/f2", 357L),
        TtsFile("espeak-ng-data/voices/!v/f3", 375L),
        TtsFile("espeak-ng-data/voices/!v/f4", 350L),
        TtsFile("espeak-ng-data/voices/!v/f5", 432L),
        TtsFile("espeak-ng-data/voices/!v/fast", 149L),
        TtsFile("espeak-ng-data/voices/!v/grandma", 263L),
        TtsFile("espeak-ng-data/voices/!v/grandpa", 256L),
        TtsFile("espeak-ng-data/voices/!v/gustave", 253L),
        TtsFile("espeak-ng-data/voices/!v/ian", 3168L),
        TtsFile("espeak-ng-data/voices/!v/iven", 261L),
        TtsFile("espeak-ng-data/voices/!v/iven2", 279L),
        TtsFile("espeak-ng-data/voices/!v/iven3", 262L),
        TtsFile("espeak-ng-data/voices/!v/iven4", 261L),
        TtsFile("espeak-ng-data/voices/!v/john", 3186L),
        TtsFile("espeak-ng-data/voices/!v/kaukovalta", 361L),
        TtsFile("espeak-ng-data/voices/!v/klatt", 38L),
        TtsFile("espeak-ng-data/voices/!v/klatt2", 38L),
        TtsFile("espeak-ng-data/voices/!v/klatt3", 39L),
        TtsFile("espeak-ng-data/voices/!v/klatt4", 39L),
        TtsFile("espeak-ng-data/voices/!v/klatt5", 39L),
        TtsFile("espeak-ng-data/voices/!v/klatt6", 39L),
        TtsFile("espeak-ng-data/voices/!v/linda", 350L),
        TtsFile("espeak-ng-data/voices/!v/m1", 335L),
        TtsFile("espeak-ng-data/voices/!v/m2", 264L),
        TtsFile("espeak-ng-data/voices/!v/m3", 300L),
        TtsFile("espeak-ng-data/voices/!v/m4", 290L),
        TtsFile("espeak-ng-data/voices/!v/m5", 262L),
        TtsFile("espeak-ng-data/voices/!v/m6", 188L),
        TtsFile("espeak-ng-data/voices/!v/m7", 254L),
        TtsFile("espeak-ng-data/voices/!v/m8", 284L),
        TtsFile("espeak-ng-data/voices/!v/marcelo", 251L),
        TtsFile("espeak-ng-data/voices/!v/max", 225L),
        TtsFile("espeak-ng-data/voices/!v/michel", 404L),
        TtsFile("espeak-ng-data/voices/!v/miguel", 382L),
        TtsFile("espeak-ng-data/voices/!v/mike2", 188L),
        TtsFile("espeak-ng-data/voices/!v/norbert", 3189L),
        TtsFile("espeak-ng-data/voices/!v/pablo", 3142L),
        TtsFile("espeak-ng-data/voices/!v/paul", 284L),
        TtsFile("espeak-ng-data/voices/!v/pedro", 352L),
        TtsFile("espeak-ng-data/voices/!v/quincy", 354L),
        TtsFile("espeak-ng-data/voices/!v/rob", 265L),
        TtsFile("espeak-ng-data/voices/!v/robert", 274L),
        TtsFile("espeak-ng-data/voices/!v/robosoft", 451L),
        TtsFile("espeak-ng-data/voices/!v/robosoft2", 454L),
        TtsFile("espeak-ng-data/voices/!v/robosoft3", 455L),
        TtsFile("espeak-ng-data/voices/!v/robosoft4", 447L),
        TtsFile("espeak-ng-data/voices/!v/robosoft5", 445L),
        TtsFile("espeak-ng-data/voices/!v/robosoft6", 287L),
        TtsFile("espeak-ng-data/voices/!v/robosoft7", 410L),
        TtsFile("espeak-ng-data/voices/!v/robosoft8", 243L),
        TtsFile("espeak-ng-data/voices/!v/sandro", 530L),
        TtsFile("espeak-ng-data/voices/!v/shelby", 280L),
        TtsFile("espeak-ng-data/voices/!v/steph", 364L),
        TtsFile("espeak-ng-data/voices/!v/steph2", 367L),
        TtsFile("espeak-ng-data/voices/!v/steph3", 377L),
        TtsFile("espeak-ng-data/voices/!v/travis", 383L),
        TtsFile("espeak-ng-data/voices/!v/victor", 253L),
        TtsFile("espeak-ng-data/voices/!v/whisper", 186L),
        TtsFile("espeak-ng-data/voices/!v/whisperf", 392L),
        TtsFile("espeak-ng-data/voices/!v/zac", 275L),
        TtsFile("espeak-ng-data/yue_dict", 563571L),
    )

    /** 全部文件的总字节数（约 215MB），用作整体进度分母 */
    val TTS_TOTAL_BYTES: Long = TTS_FILES.sumOf { it.bytes }

    /** 下载状态：Idle / Running(已下, 总量) / Done / Failed(原因)。App 级单例，页面切换不丢 */
    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Running(val done: Long, val total: Long) : DownloadState
        data object Done : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    // App 级下载 scope：设置页关闭后下载继续，回页重进仍能看到进度
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    fun ttsDir(context: Context): File = File(File(context.filesDir, "tts"), "kokoro-v1.1-zh")

    /** 全部文件都已下载且尺寸达标才算就绪 */
    fun isTtsReady(context: Context): Boolean {
        val dir = ttsDir(context)
        return TTS_FILES.all { File(dir, it.relPath).length() >= it.bytes }
    }

    /** 已下载字节（含未完成的 .part），用于整体进度与续传提示 */
    fun downloadedBytes(context: Context): Long {
        val dir = ttsDir(context)
        return TTS_FILES.sumOf { f ->
            val done = File(dir, f.relPath)
            if (done.exists()) done.length()
            else File(dir, "${f.relPath}.part").takeIf { it.exists() }?.length() ?: 0L
        }
    }

    fun deleteTtsModel(context: Context) {
        downloadJob?.cancel()
        downloadJob = null
        _state.value = DownloadState.Idle
        ttsDir(context).deleteRecursively()
    }

    /** 启动下载（幂等：正在下就忽略）。调用方给 applicationContext，防泄漏页面 */
    fun startDownload(appContext: Context) {
        if (downloadJob?.isActive == true) return
        downloadJob = downloadScope.launch {
            try {
                downloadAll(appContext)
                _state.value = DownloadState.Done
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // .part 全保留：下次再点从断点续传
                _state.value = DownloadState.Failed(e.message ?: "下载失败")
            }
        }
    }

    private suspend fun downloadAll(context: Context) = withContext(Dispatchers.IO) {
        val dir = ttsDir(context)
        val completed = java.util.concurrent.atomic.AtomicLong(0L)
        // hf-mirror 每文件一次完整 HTTPS 往返（TLS+重定向，实测 10 秒级/个），espeak 300+ 小文件
        // 串行要 40 分钟——8 路并发把小文件阶段压到 3~5 分钟；单文件内部重试/续传不受影响
        val sem = Semaphore(8)
        coroutineScope {
            for (f in TTS_FILES) {
                launch {
                    sem.withPermit {
                        val dst = File(dir, f.relPath)
                        if (dst.length() >= f.bytes) {
                            completed.addAndGet(f.bytes)
                            _state.value = DownloadState.Running(completed.get(), TTS_TOTAL_BYTES)
                            return@withPermit
                        }
                        // 并发下不逐字节汇报（进度粒度=每文件完成），完成后推进整体进度
                        downloadFile(dir, f.relPath, "$TTS_REPO/${f.relPath}", f.bytes) { }
                        completed.addAndGet(f.bytes)
                        _state.value = DownloadState.Running(completed.get(), TTS_TOTAL_BYTES)
                    }
                }
            }
        }
    }

    /** 下载单个文件（.part 断点续传 + 200 自愈 + 3 次重试），完成后原子改名到位 */
    private suspend fun downloadFile(
        dir: File,
        relPath: String,
        url: String,
        minBytes: Long,
        onProgress: (Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val dst = File(dir, relPath)
        dst.parentFile?.mkdirs()
        val part = File(dir, "$relPath.part")
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                var start = if (part.exists()) part.length() else 0L
                val conn = open(url)
                if (start > 0) conn.setRequestProperty("Range", "bytes=$start-")
                try {
                    val code = conn.responseCode
                    if (code != 200 && code != 206) throw java.io.IOException("HTTP $code")
                    // 200 = 服务端不认 Range（换源/不支持），弃 .part 从头下
                    val append = code == 206 && start > 0
                    if (!append) {
                        start = 0
                        if (part.exists()) part.delete()
                    }
                    val total = conn.contentLengthLong.let {
                        if (it > 0) it + if (append) start else 0 else maxOf(start, minBytes)
                    }
                    conn.inputStream.use { input ->
                        FileOutputStream(part, append).use { fos ->
                            val buf = ByteArray(64 * 1024)
                            var read = start
                            var sinceReport = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                fos.write(buf, 0, n)
                                read += n
                                sinceReport += n
                                if (sinceReport >= 256 * 1024) {
                                    sinceReport = 0
                                    onProgress(read)
                                }
                            }
                            fos.flush()
                            onProgress(read)
                            if (read < minBytes) throw java.io.IOException("下载的文件不完整（$read < $minBytes）")
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                if (dst.exists()) dst.delete()
                if (!part.renameTo(dst)) {
                    part.copyTo(dst, overwrite = true)
                    part.delete()
                }
                return@withContext
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < 2) delay(1500L * (attempt + 1))
            }
        }
        throw lastError ?: java.io.IOException("下载失败")
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
}
