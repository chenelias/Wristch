# Wristch

> Built at the 2026 FUTUREMODE × SITCON Hackathon with ANYI

讓手機幫你處理繁瑣的事，專注在真正重要的事上。

## 專案緣起

在現代生活中，我們花了太多時間在手機上處理瑣碎的任務：傳訊息、查行事曆、找餐廳、複製貼上⋯⋯這些事情不難，但很煩人。

Wristch 的目標是讓你用一句話完成這些事。只要用語音說出你想做的事，AI 就會幫你在手機上操作完成。

**舉例：**

- 「傳訊息給老師說我明天想討論報告」→ AI 自動打開通訊軟體、找到老師、根據你們之前的對話語氣撰寫訊息
- 「叫哥哥來這裡吃飯」→ AI 自動帶入你目前所在餐廳的資訊和 Google Maps 連結

## 主要功能

- **一句話下任務**：語音或文字輸入，Agent 直接在手機上完成操作
- **執行過程可見、可控**：懸浮狀態列顯示 Agent 正在做什麼，敏感動作會先跳出確認，隨時可以中止
- **Vibe**：針對不同情境預設語氣、確認層級與要帶入的情境資訊
- **記憶**：把常用的個人資訊記下來，之後的任務自動沿用
- **歷史紀錄**：每次執行都留下逐步的紀錄，可回頭查看做了哪些操作
- **情境來源**：聯絡人、行事曆、位置、訊息等資料，只有在 Vibe 允許時才會被讀取

## 技術架構

### AI Agent（核心）

我們使用 **Gemini API** 的 **Computer Use** 功能，讓 AI 能夠：

- 截取手機畫面
- 理解畫面內容
- 執行點擊、滑動、輸入等操作

> 💡 **為什麼選 Gemini？**
>
> 原本打算使用 Google 的 [Koog](https://github.com/nicholasgriffintn/koog)（Kotlin AI Agent 框架），但實測後發現 Gemini 原生的 Computer Use API 整合起來更簡單、也更穩定。直接用 `com.google.genai` SDK 就能完成所有事情，不需要額外的抽象層。

### Vibe 系統

不同情境需要不同的溝通方式。Vibe 讓你預先設定：

- **提示詞**：告訴 AI 該用什麼語氣、該注意什麼
- **確認層級**：每個動作都問 / 敏感操作才問 / 完全自動
- **情境資訊**：自動帶入相關的背景資料

### 語音輸入

- 使用 Android 內建語音辨識取得初步文字
- 再透過 Gemini 修正文法、去除口語贅詞
- 修正完成後自動送出

## 關於 WearOS

專案初期做過一個 WearOS 手勢辨識原型（TensorFlow Lite 讀 IMU 資料，參考 Apple 的論文 [_Enabling Hand Gesture Customization on Wrist-Worn Devices_](https://arxiv.org/abs/2203.15239)），想用握拳、捏指等手勢觸發手機端不同 Vibe 的 Agent，但最後因時間不足，沒能和手機 App 完整串接，因此本專案的重心與完成度都在手機 App 上。

## 系統需求

- **手機**：Android 15+（API 35）
- **Gemini API Key**：在 `local.properties` 加入 `geminiApiKey=你的金鑰`

## 專案結構

```
.
├── app/              # 主要程式碼
│   └── src/main/java/dev/eliaschen/wristch/
│       ├── accessibility/  # 畫面讀取與操作執行
│       ├── computer/       # AI Agent 核心邏輯
│       ├── context/        # 情境來源（聯絡人、行事曆、位置⋯⋯）
│       ├── history/        # 執行紀錄
│       ├── memory/         # 記憶
│       ├── settings/       # 設定
│       ├── ui/             # Compose UI
│       ├── vibe/           # Vibe 系統
│       └── voice/          # 語音輸入
└── gradle/           # 依賴管理
```

_讓科技回歸它該有的樣子：幫你做事，而不是佔用你的時間。_
