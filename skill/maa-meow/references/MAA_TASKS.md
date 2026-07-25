# MaaCore 任务能力参考

用户问「MAA 能不能做 X」「有没有练级/专精任务」时读本文；完整协议见 [官方集成文档](https://docs.maa.plus/zh-cn/protocol/integration.html)。

本 skill 日常运维仍以 [SKILL.md](../SKILL.md) 为准；本文侧重 **任务语义** 与 HTTP/`meow_sse.sh` 下发。

---

## 任务类型一览

| 类型 | 做什么 | 和练级/专精 |
|---|---|---|
| `StartUp` | 唤醒客户端、可选切账号 | — |
| `CloseDown` | 关闭游戏 | — |
| `Fight` | 理智作战、刷图 | 编队干员拿经验，**不能**指定练到某级 |
| `Copilot` | 抄作业编队作战 | 可校验作业要求的精英/等级，**不帮练** |
| `SSSCopilot` / `ParadoxCopilot` | 保全/悖论模拟器作业 | — |
| `Recruit` | 公招、刷新、加速 | — |
| `Infrast` | 基建换班 | **训练室专精**（见下） |
| `Mall` | 信用商店、拜访好友 | — |
| `Award` | 领日常/邮件/免费单抽等 | — |
| `Roguelike` | 肉鸽刷分/源石锭等 | 识别练度，非定向培养 |
| `Reclamation` | 生息演算 | — |
| `Depot` | 仓库识别 | — |
| `OperBox` | 干员 box 识别（持有/潜能/练度） | **只读**，不练级 |
| `Custom` | 按名称执行自定义 task 链 | 可串 StartUp→Infrast→Fight 等 |

---

## 练级 / 专精：能做什么

**没有**「把某干员练到 X 级 / 精二 / 专精 Y 级」的独立任务。相关能力如下：

### 技能专精 — `Infrast` + 训练室 `Training`

专精走基建，须在 `facility` 数组里包含 **`Training`**。

| 能力 | 说明 |
|---|---|
| 检测训练完成 | 识别「专精等级 N 训练完成」 |
| 自动领取 | 完成后自动收 |
| 继续专精 | `continue_training: true` 时自动接下一级（专精 1→2→3） |
| 自动取材料 | 材料够时从仓库取 |

**注意：**

1. **需先在游戏里**把干员放进训练室并选好技能；MAA 不会从零选人、选技能。
2. **`Training` 应排在 `Dorm` 前面**，否则换班可能把训练干员撤下来。
3. 材料不齐会停。
4. 自定义基建 JSON（[基建排班协议](https://docs.maa.plus/zh-cn/protocol/base-scheduling-schema.html)）**不能**像制造站那样指定训练室干员；专精靠「手动进驻 + MAA 巡检/续专」。

`continue_training` 在官方集成文档里未必列全，但 MaaCore 源码支持：

```json
{
  "type": "Infrast",
  "params": {
    "enable": true,
    "mode": 0,
    "facility": ["Control", "Mfg", "Trade", "Training", "Dorm"],
    "continue_training": true,
    "threshold": 0.3
  }
}
```

`mode`：`0` 默认换班 · `10000` 读自定义 JSON · `20000` 一键轮换（跳过宿舍等）。

### 干员等级 / 精英化

| 途径 | 说明 |
|---|---|
| `Fight` 反复刷 | 间接升级，不能指定目标等级 |
| `Roguelike` | 账号/源石锭向，非定向培养 |
| `OperBox` | 读取 elite / level / 潜能 |
| `Copilot` | 较新版本可校验是否满足作业练度要求 |

---

## 本 skill 怎么下发

入口：`meow_sse.sh` + JSON 字符串（见 [SKILL.md](../SKILL.md)）。`run_tasks.sh` 的 MODE 仅 legacy。

```bash
# 基建专精（游戏里需已安排训练室干员）
bash ~/.cursor/skills/maa-meow/scripts/meow_sse.sh '{
  "force_stop_game": false,
  "closedown_after": false,
  "tasks": [
    {"type":"StartUp","params":{"client_type":"Official","start_game_enabled":true}},
    {"type":"Infrast","params":{
      "enable":true,"mode":0,
      "facility":["Training","Dorm"],
      "continue_training":true,
      "threshold":0.3
    }}
  ]
}'
```

默认 **`force_stop_game=false`**、**`closedown_after=false`**。详情见 [TASK_ORCHESTRATION.md](./TASK_ORCHESTRATION.md)。

---

## 常见需求速查

| 需求 | 支持？ | 做法 |
|---|---|---|
| 某技能专精 1→2→3（已进驻、材料齐） | ✅ | `Infrast` + `continue_training` |
| 指定干员练到某等级/精英化 | ❌ | 只能 `Fight` 反复刷 |
| 刷图顺便升级 | ✅ | `Fight` |
| 查看当前练度 | ✅ | `OperBox` / `operbox_lib.py show` |
| 公招 / 基建全设施 / 肉鸽 | ✅ | `TASKS_JSON` 拼对应 `type` |

---

## 官方文档

- [集成文档（任务参数）](https://docs.maa.plus/zh-cn/protocol/integration.html)
- [基建排班协议](https://docs.maa.plus/zh-cn/protocol/base-scheduling-schema.html)
- [基建使用说明](https://docs.maa.plus/zh-cn/manual/introduction/infrastructure.html)
