# JH GetWay

## সম্পূর্ণ Product Requirements & AI Studio Build Specification

**Product:** JH GetWay\
**Organization:** JH Soft Corporation\
**Document:** Product Definition, Features, System Behavior & UI/UX
Specification\
**Target:** Google AI Studio-তে একদম নতুন করে Application তৈরি\
**Release Target:** Version 1.0\
**Document Status:** Master Build Specification

------------------------------------------------------------------------

# 1. প্রজেক্ট পরিচিতি

JH GetWay হবে একটি ব্যক্তিগত ও সম্প্রসারণযোগ্য **AI Gateway Management
Platform**। এর মাধ্যমে বিভিন্ন Cloud AI Provider, Local AI Model, Coding
Agent, MCP Server এবং AI Workflow একটি কেন্দ্রীয় জায়গা থেকে যুক্ত, নিয়ন্ত্রণ,
পরীক্ষা ও পর্যবেক্ষণ করা যাবে।

এটি শুধু API Key রাখার অ্যাপ নয়। এটি হবে এমন একটি কেন্দ্রীয় ব্যবস্থা, যার
মাধ্যমে:

-   একাধিক AI Provider-এর API এক জায়গায় সংরক্ষণ ও পরিচালনা করা যাবে।
-   একটি Unified API Endpoint দিয়ে বিভিন্ন AI Model ব্যবহার করা যাবে।
-   কোনো Provider ব্যর্থ বা Rate Limited হলে নিয়ম অনুযায়ী অন্য Provider
    ব্যবহার করা যাবে।
-   OpenCode, Codex এবং অন্যান্য সমর্থিত Coding Agent-কে Gateway-এর সঙ্গে
    যুক্ত করা যাবে।
-   Local AI Server যেমন Ollama বা llama.cpp যুক্ত করা যাবে।
-   MCP Server ও তার Tools পরিচালনা করা যাবে।
-   একাধিক AI Model ও Tool দিয়ে Workflow তৈরি ও চালানো যাবে।
-   API Usage, Token, Latency, Error এবং Provider Health দেখা যাবে।
-   AI Playground থেকে Model পরীক্ষা ও তুলনা করা যাবে।
-   একাধিক Device ও Application-কে Gateway-এর সঙ্গে যুক্ত করা যাবে।
-   API Credentials নিরাপদভাবে সংরক্ষণ করা যাবে।

## 1.1 মূল লক্ষ্য

**One Gateway. Complete AI Infrastructure.**

JH GetWay হবে JH Soft Corporation-এর বিভিন্ন AI Application ও Development
Tool-এর কেন্দ্রীয় AI Gateway।

## 1.2 কাদের জন্য

-   ব্যক্তিগত Developer
-   AI Application Developer
-   Coding Agent ব্যবহারকারী
-   Local AI Model ব্যবহারকারী
-   একাধিক API Provider পরিচালনাকারী
-   নিজের AI Workflow তৈরি করতে চাওয়া ব্যবহারকারী
-   একাধিক Device থেকে একই Gateway ব্যবহার করতে চাওয়া ব্যবহারকারী

------------------------------------------------------------------------

# 2. অ্যাপ্লিকেশনের মূল অংশ

Application-টি নিচের প্রধান বিভাগগুলো নিয়ে তৈরি হবে।

1.  Dashboard
2.  Providers
3.  Models
4.  API Gateway
5.  Smart Router
6.  Coding Agents
7.  Local AI
8.  MCP Servers
9.  Workflows
10. Playground
11. Analytics
12. Devices
13. API Key Vault
14. Activity & Logs
15. Settings

প্রতিটি বিভাগ বাস্তব কার্যকরী হতে হবে। শুধু Demo Button, Fake Data, Static
Screen অথবা কাজ না করা Menu রাখা যাবে না।

------------------------------------------------------------------------

# 3. User Experience ও Navigation

## 3.1 Simple Navigation

Mobile Application-এর জন্য নিচের Bottom Navigation ব্যবহার করতে হবে:

-   Home
-   Providers
-   Playground
-   Analytics
-   Settings

অন্য সব Feature Home Dashboard এবং Navigation Drawer/More Menu থেকে পাওয়া
যাবে।

Navigation এমন হতে হবে যাতে ব্যবহারকারী অল্প কয়েকটি Tap-এ প্রয়োজনীয়
Feature-এ পৌঁছাতে পারে।

## 3.2 UI Design Principles

-   Simple, Clean, Modern এবং Professional Design।
-   অপ্রয়োজনীয় Card, Animation ও Decoration ব্যবহার করা যাবে না।
-   প্রতিটি Screen-এ একটি স্পষ্ট Primary Action থাকবে।
-   গুরুত্বপূর্ণ Status সহজে বোঝা যাবে।
-   Long Form-কে ছোট ছোট Section-এ ভাগ করতে হবে।
-   Loading, Empty, Success ও Error State থাকতে হবে।
-   Mobile Screen-এ ব্যবহারযোগ্য হতে হবে।
-   ছোট Screen-এ কোনো Text বা Button কেটে যাওয়া যাবে না।
-   Accessibility ও Readability বজায় রাখতে হবে।
-   একই ধরনের Button, Icon, Form ও Status-এর Design পুরো App-এ একই রাখতে
    হবে।

## 3.3 Light ও Dark Mode

দুটি Theme থাকবে:

**Light Mode** - Default Theme। - সাদা বা হালকা Neutral Background। -
গাঢ় Text। - পরিষ্কার Border ও Subtle Shadow। - Blue/Purple Accent
সীমিতভাবে ব্যবহার।

**Dark Mode** - Dark Navy বা Charcoal Background। - হালকা Text। - Subtle
Border। - একই Accent Color। - অতিরিক্ত Neon Glow বা চোখে লাগে এমন Effect
নয়।

**Theme Settings** - Light - Dark - System Default

Theme পরিবর্তন করলে পুরো Application সঙ্গে সঙ্গে পরিবর্তিত হবে। ব্যবহারকারীর
নির্বাচন সংরক্ষিত থাকবে।

## 3.4 বাংলা ও English Language

Application-এ সম্পূর্ণ বাংলা ও English Language Support থাকতে হবে।

**Language Options** - বাংলা - English - System Default (যদি সমর্থিত হয়)

**আচরণ** - Language পরিবর্তন করলে সব Menu, Button, Label, Form, Error,
Notification, Empty State এবং Settings Text পরিবর্তিত হবে। - বাংলা ও
English Text-এর জন্য Layout ভেঙে যাওয়া চলবে না। - User-generated Prompt,
API Response, Model Name ও Provider Name অনুবাদ করা যাবে না। - Date, Time
ও Number Format নির্বাচিত Language অনুযায়ী দেখানো যেতে পারে। - Language
নির্বাচন সংরক্ষিত থাকবে।

------------------------------------------------------------------------

# 4. Dashboard

Dashboard হবে Application-এর Main Overview Screen।

## 4.1 Dashboard-এ যা থাকবে

### Gateway Status

-   Gateway Running / Stopped / Offline
-   Gateway Address
-   Connected Devices
-   Active Requests
-   Gateway Start/Stop বা Connect/Disconnect Action

### Provider Overview

-   মোট Provider
-   Active Provider
-   Failed Provider
-   Rate Limited Provider

### Model Overview

-   মোট Model
-   Available Model
-   Local Model
-   Cloud Model

### Usage Overview

-   আজকের Request
-   মোট Request
-   Input Token
-   Output Token
-   Estimated Cost (যেখানে Pricing Data পাওয়া যায়)

### Performance Overview

-   Average Latency
-   Success Rate
-   Error Count
-   Fallback Count

### Recent Activity

-   সর্বশেষ API Request
-   Provider Connection Event
-   Key Validation Event
-   Workflow Execution
-   Device Connection
-   Error Event

## 4.2 Dashboard Interaction

-   Status Card চাপলে সংশ্লিষ্ট বিস্তারিত Screen খুলবে।
-   Provider Summary চাপলে Providers Screen খুলবে।
-   Request Summary চাপলে Analytics বা Request Logs খুলবে।
-   Gateway Status চাপলে Gateway Control Screen খুলবে।
-   কোনো Data না থাকলে Fake Number দেখানো যাবে না; Empty State দেখাতে
    হবে।

------------------------------------------------------------------------

# 5. Provider Management

Provider হলো বাইরের AI Service বা API Platform, যার মাধ্যমে AI Model
ব্যবহার করা হয়।

## 5.1 Provider Examples

-   Google Gemini
-   OpenAI
-   Anthropic
-   OpenRouter
-   Groq
-   Mistral
-   Hugging Face
-   DeepInfra
-   Cohere
-   Custom OpenAI-Compatible Provider
-   অন্যান্য Provider

Provider List পরিবর্তনযোগ্য হতে হবে। নতুন Provider যুক্ত করতে Application-এর
পুরো Structure পরিবর্তন করতে হবে না।

## 5.2 Provider Add

Add Provider Form-এ থাকবে:

-   Provider Name
-   Provider Type
-   API Base URL
-   API Key
-   API Format
-   Default Model (ঐচ্ছিক)
-   Priority
-   Enabled/Disabled
-   Custom Headers (শুধু প্রয়োজন হলে)
-   Notes (ঐচ্ছিক)

Provider Type: - Built-in Provider - OpenAI-Compatible -
Anthropic-Compatible - Custom API Adapter

## 5.3 Provider Actions

প্রতিটি Provider-এর জন্য:

-   Add
-   Edit
-   Enable
-   Disable
-   Test Connection
-   Fetch Models
-   View Usage
-   View Errors
-   Delete

Delete করার আগে Confirmation নিতে হবে। কোনো Provider Delete করলে তার
সঙ্গে যুক্ত Routing Policy বা Model Configuration-এর প্রভাব ব্যবহারকারীকে
জানাতে হবে।

## 5.4 Provider Status

Status: - Connected - Disconnected - Checking - Error - Rate Limited -
Disabled - Unknown

Status কেবল বাস্তব Connection Test বা Request Result থেকে নির্ধারিত হবে।

## 5.5 Test Connection

Test Connection চাপলে:

1.  Provider Configuration যাচাই হবে।
2.  API Key আছে কি না যাচাই হবে।
3.  Provider-এর উপযুক্ত Health বা Model Endpoint-এ Request যাবে।
4.  Response অনুযায়ী Status দেখাবে।
5.  Latency ও Error Message দেখাবে।
6.  API Key বা Secret কখনো Error Message-এ প্রকাশ করা যাবে না।

------------------------------------------------------------------------

# 6. Model Management

Model হলো Provider-এর AI Model, যেটি Request Processing করবে।

## 6.1 Model List

প্রতিটি Model-এর জন্য দেখাতে হবে:

-   Model Name
-   Model ID
-   Provider
-   Model Type
-   Availability
-   Context Window (জানা থাকলে)
-   Input Capability
-   Output Capability
-   Tool Calling Support
-   Streaming Support
-   Local/Cloud
-   Pricing Information (জানা থাকলে)

## 6.2 Model Type

-   General Chat
-   Coding
-   Reasoning
-   Vision
-   Image Generation
-   Embedding
-   Audio
-   Multimodal
-   Custom

একটি Model একাধিক Capability সমর্থন করতে পারে।

## 6.3 Model Management Actions

-   Fetch Models
-   Refresh
-   Enable/Disable
-   Set Default
-   Set Alias
-   View Details
-   Test Model
-   Compare Model

## 6.4 Model Alias

ব্যবহারকারী নিজের সুবিধার জন্য Alias তৈরি করতে পারবে।

উদাহরণ:

-   `jh-general`
-   `jh-coder`
-   `jh-fast`
-   `jh-reasoning`
-   `jh-low-cost`
-   `jh-vision`

Alias একটি নির্দিষ্ট Model অথবা Routing Policy-এর সঙ্গে যুক্ত হবে।

Alias-এর নাম পরিবর্তন করলেও আসল Provider Model ID পরিবর্তন হবে না।

------------------------------------------------------------------------

# 7. Universal AI Gateway

এটি JH GetWay-এর প্রধান Service। অন্য Application এই Gateway ব্যবহার করে AI
Request পাঠাতে পারবে।

## 7.1 Unified Endpoint

OpenAI-Compatible Client-এর জন্য প্রধান Endpoint:

`/v1/chat/completions`

অন্যান্য প্রয়োজনীয় Endpoint:

-   `/v1/models`
-   `/v1/responses`
-   `/v1/embeddings`
-   `/v1/images/generations`
-   `/v1/audio/transcriptions`
-   `/v1/audio/speech`

Anthropic-Compatible Endpoint:

-   `/v1/messages`

প্রতিটি Endpoint সংশ্লিষ্ট API Contract অনুযায়ী কাজ করতে হবে। সব Provider-এর
API এক রকম ধরে নেওয়া যাবে না।

## 7.2 Gateway Request Flow

1.  Client Gateway Address-এ Request পাঠাবে।
2.  Gateway API Token যাচাই করবে।
3.  Request Format যাচাই করবে।
4.  Model ID বা Alias শনাক্ত করবে।
5.  Routing Policy অনুযায়ী Provider নির্বাচন করবে।
6.  Provider Adapter Request তৈরি করবে।
7.  Provider API-তে Request পাঠাবে।
8.  Response Normalize করা হবে।
9.  Usage Information সংগ্রহ করা হবে।
10. Client-কে Response পাঠানো হবে।
11. Request Metadata Analytics-এ সংরক্ষিত হবে।

## 7.3 Streaming

যদি Client `stream: true` পাঠায়:

-   Gateway Streaming Response দেবে।
-   Response Chunk অনুযায়ী পাঠানো হবে।
-   Client-এর Connection বন্ধ হলে Request Cancel করার চেষ্টা করবে।
-   Stream-এর মাঝখানে Error হলে সঠিকভাবে Error Event বা Connection
    Failure জানাবে।
-   Stream শুরু হওয়ার পর একই Response অন্য Provider থেকে নতুন করে শুরু করা
    যাবে না।

## 7.4 Gateway Settings

-   Gateway Name
-   Gateway Address
-   Default Model
-   Default Provider Policy
-   Request Timeout
-   Maximum Request Size
-   Streaming Enabled
-   Logging Level
-   Authentication Required
-   Allowed Clients
-   Rate Limit

Gateway Address Local অথবা Remote হতে পারে।

------------------------------------------------------------------------

# 8. Smart API Router

Smart Router নির্ধারণ করবে কোন Request কোন Provider ও Model-এ যাবে।

## 8.1 Routing Mode

### Manual Mode

ব্যবহারকারী নির্দিষ্ট Provider ও Model নির্বাচন করবে।

### Automatic Mode

Gateway Request-এর প্রয়োজন, Model Capability, Provider Status এবং Routing
Rules বিবেচনা করে Provider নির্বাচন করবে।

### Priority Mode

ব্যবহারকারীর নির্ধারিত Priority অনুযায়ী Provider নির্বাচন হবে।

### Round Robin

যোগ্য Provider-গুলোর মধ্যে পর্যায়ক্রমে Request পাঠানো হবে।

### Lowest Latency

সাম্প্রতিক Latency Data অনুযায়ী দ্রুত Provider নির্বাচন করা হবে।

### Lowest Cost

যেখানে নির্ভরযোগ্য Pricing Data আছে, সেখানে আনুমানিক কম খরচের Provider
নির্বাচন হবে।

### Local First

সমর্থিত Local Model থাকলে আগে Local Model ব্যবহার করা হবে। না থাকলে
অনুমোদিত Cloud Provider ব্যবহার করা যাবে।

### Cloud First

Cloud Provider আগে ব্যবহার হবে। প্রয়োজনে Local Model ব্যবহার করা যাবে।

## 8.2 Routing Rule

ব্যবহারকারী Rule তৈরি করতে পারবে:

-   Model Alias
-   Allowed Providers
-   Preferred Provider
-   Backup Providers
-   Priority
-   Required Capabilities
-   Maximum Cost (যেখানে Pricing Data নির্ভরযোগ্য)
-   Maximum Latency
-   Local/Cloud Preference
-   Enabled/Disabled

## 8.3 Fallback System

Provider ব্যর্থ হলে Gateway পরবর্তী উপযুক্ত Provider চেষ্টা করবে।

Fallback Trigger:

-   Rate Limit
-   Provider Server Error
-   Connection Failure
-   Timeout
-   Model Unavailable
-   Invalid/Expired Credential (Key disable বা alternate key থাকলে)

Fallback করার আগে Request নিরাপদে Retry করা যাবে কি না যাচাই করতে হবে।
Non-idempotent Request অন্ধভাবে পুনরায় পাঠানো যাবে না।

## 8.4 Router Monitoring

দেখাতে হবে:

-   Selected Provider
-   Selected Model
-   Routing Reason
-   Fallback Used
-   Attempt Count
-   Latency
-   Final Status

------------------------------------------------------------------------

# 9. Coding Agent Gateway

JH GetWay ব্যবহার করে Coding Agent-কে AI Model-এর সঙ্গে যুক্ত করা যাবে।

## 9.1 Supported Clients

-   OpenCode
-   Codex
-   Continue
-   Cline
-   Aider
-   Other Compatible Coding Clients

সমর্থন নির্ভর করবে প্রতিটি Client-এর API Contract ও প্রয়োজনীয় Feature-এর
ওপর।

## 9.2 Agent Profile

প্রতিটি Agent-এর জন্য:

-   Agent Name
-   Agent Type
-   Gateway Base URL
-   API Token
-   Default Model
-   Model Alias
-   Streaming
-   Tool Calling
-   Allowed Models
-   Usage Limit (ঐচ্ছিক)
-   Enabled/Disabled

## 9.3 Agent Configuration

উদাহরণ:

``` text
Base URL: https://your-gateway-address/v1
API Key: [Generated Gateway Token]
Model: jh-coder
```

## 9.4 Agent Features

-   Agent Profile Create
-   Edit
-   Delete
-   Connection Test
-   Model Assignment
-   Token Management
-   Usage Tracking
-   Request History
-   Enable/Disable

Codex বা অন্য কোনো Client-এর জন্য বিশেষ API Contract প্রয়োজন হলে সেটি আলাদা
Compatibility Integration হিসেবে বাস্তবায়ন করতে হবে।

------------------------------------------------------------------------

# 10. Local AI Bridge

Local AI Bridge দিয়ে নিজের Network বা Device-এ চালানো AI Model যুক্ত করা
যাবে।

## 10.1 Supported Engines

-   Ollama
-   llama.cpp
-   LM Studio
-   LocalAI
-   OpenAI-Compatible Local Server
-   Custom Local Endpoint

## 10.2 Add Local Server

Fields:

-   Server Name
-   Server URL
-   Engine Type
-   Authentication (যদি থাকে)
-   Connection Timeout
-   Default Model
-   Enabled/Disabled

## 10.3 Local AI Actions

-   Add Server
-   Test Connection
-   Discover Models
-   Refresh Models
-   Test Model
-   Set Default
-   Enable/Disable
-   Remove Server

## 10.4 Local Routing

ব্যবহারকারী নির্বাচন করতে পারবে:

-   Local Only
-   Local First
-   Cloud First
-   Local + Cloud Fallback

Local Server unavailable হলে Cloud Fallback কেবল ব্যবহারকারীর অনুমোদিত
Policy থাকলেই হবে।

## 10.5 Network Safety

-   Public ও Private Address আলাদা করে দেখাতে হবে।
-   Arbitrary URL Request প্রতিরোধ করতে হবে।
-   HTTPS Certificate যাচাই করতে হবে।
-   Local Network Access স্পষ্টভাবে অনুমোদিত হতে হবে।
-   Untrusted Server-এ Credential পাঠানো যাবে না।

------------------------------------------------------------------------

# 11. MCP Server Manager

MCP (Model Context Protocol) Server ও Tools পরিচালনার জন্য আলাদা Section
থাকবে।

## 11.1 MCP Server Add

Fields:

-   Server Name
-   Transport Type
-   Server Address অথবা Command
-   Arguments (STDIO হলে)
-   Environment Variables (Secret Masked)
-   Authentication
-   Connection Settings
-   Enabled/Disabled

## 11.2 Supported Transport

-   STDIO
-   Streamable HTTP

অন্য Transport ভবিষ্যতে প্রয়োজন অনুযায়ী যুক্ত করা যাবে।

## 11.3 MCP Dashboard

প্রতিটি Server-এর জন্য:

-   Server Name
-   Connection Status
-   Transport
-   Available Tools
-   Last Connected
-   Last Error
-   Enable/Disable

## 11.4 MCP Features

-   Add Server
-   Connect
-   Disconnect
-   Reconnect
-   Discover Tools
-   Discover Resources
-   Discover Prompts
-   Execute Tool
-   View Tool Result
-   View Logs
-   Edit Permissions
-   Remove Server

## 11.5 Tool Permission

প্রতিটি Tool-এর Permission থাকবে:

-   Allowed
-   Ask Before Running
-   Denied

Sensitive বা Destructive Tool-এর ক্ষেত্রে User Approval প্রয়োজন হবে।

Tool Execution-এ Timeout, Output Limit, Permission Check এবং Audit Log
থাকতে হবে।

------------------------------------------------------------------------

# 12. AI Workflow Engine

AI Workflow Engine দিয়ে একাধিক AI Model, API এবং MCP Tool যুক্ত করে কাজ
করানো যাবে।

## 12.1 Workflow Builder

একটি সহজ Visual Builder থাকবে।

ব্যবহারকারী Node যোগ করবে এবং Connection দিয়ে Node-গুলো যুক্ত করবে।

## 12.2 Node Types

1.  Input Node
2.  AI Model Node
3.  Provider Node
4.  Condition Node
5.  Router Node
6.  HTTP Request Node
7.  MCP Tool Node
8.  Text Transform Node
9.  Parallel Node
10. Merge Node
11. Output Node

## 12.3 Example Workflow

``` text
User Input
    |
    v
AI Analysis
    |
    v
Condition
   / \
  /   \
Code   General
 |       |
Coding  Chat
Model   Model
  \     /
   \   /
    Merge
      |
      v
    Output
```

## 12.4 Workflow Management

-   Create Workflow
-   Edit Workflow
-   Rename
-   Duplicate
-   Delete
-   Save
-   Run
-   Stop
-   View Execution
-   View Node Output
-   Retry Failed Node
-   Export
-   Import

## 12.5 Workflow Execution Rules

-   Node Dependency যাচাই করতে হবে।
-   Invalid Connection থাকলে Run বন্ধ থাকবে।
-   Cycle Detection থাকতে হবে।
-   Node Timeout থাকতে হবে।
-   Maximum Execution Step থাকতে হবে।
-   Retry Policy থাকতে হবে।
-   Failed Node-এর Error দেখাতে হবে।
-   Execution History রাখতে হবে।
-   User চাইলে Workflow Cancel করতে পারবে।

## 12.6 Workflow Template

কিছু Built-in Template থাকবে:

-   AI Research
-   Code Review
-   Text Summarization
-   Multi-Model Comparison
-   Document Analysis
-   AI Content Processing

Template-গুলো বাস্তবভাবে কাজ করতে হবে এবং ব্যবহারকারী Edit করতে পারবে।

------------------------------------------------------------------------

# 13. AI Playground

AI Playground হবে Model Test করার জায়গা।

## 13.1 Playground Layout

-   Conversation Area
-   Model Selector
-   Provider Selector
-   System Prompt
-   Message Input
-   Send Button
-   Streaming Indicator
-   Response Information

## 13.2 Model Parameters

যে Model সমর্থন করে তার জন্য:

-   Temperature
-   Top P
-   Maximum Output Tokens
-   Stop Sequences
-   Streaming
-   JSON Output
-   Tool Calling

Unsupported Parameter-এর জন্য UI-তে পরিষ্কারভাবে Unsupported দেখাতে হবে।

## 13.3 Multi-Model Compare

একই Prompt একাধিক Model-এ পাঠানো যাবে।

তুলনার বিষয়:

-   Response
-   Latency
-   Input Tokens
-   Output Tokens
-   Estimated Cost
-   Error
-   Model/Provider

## 13.4 Conversation Management

-   New Chat
-   Rename Chat
-   Delete Chat
-   Clear Chat
-   Copy Response
-   Retry
-   Regenerate
-   Export Conversation

Conversation Save করার আগে Privacy Settings অনুসরণ করতে হবে।

------------------------------------------------------------------------

# 14. API Analytics

Analytics Screen-এ Gateway Usage ও Performance দেখা যাবে।

## 14.1 Overview

-   Total Requests
-   Successful Requests
-   Failed Requests
-   Total Input Tokens
-   Total Output Tokens
-   Estimated Cost
-   Average Latency
-   P95 Latency
-   Fallback Count

## 14.2 Filters

-   Today
-   Last 7 Days
-   Last 30 Days
-   Custom Date Range
-   Provider
-   Model
-   Device
-   Status

## 14.3 Charts

-   Requests Over Time
-   Token Usage
-   Provider Usage
-   Model Usage
-   Latency
-   Error Rate
-   Estimated Cost

## 14.4 Request Details

প্রতিটি Request-এর জন্য:

-   Request ID
-   Time
-   Provider
-   Model
-   Device
-   Status
-   Latency
-   Token Usage
-   Fallback Attempts
-   Error Code

Default অবস্থায় Full Prompt, API Key বা Sensitive Response সংরক্ষণ করা
যাবে না।

## 14.5 Cost Calculation

-   Provider Pricing Data ব্যবহার করতে হবে।
-   Pricing Data না থাকলে Unknown দেখাতে হবে।
-   Estimated Cost এবং Actual Billed Cost আলাদা রাখতে হবে।
-   Currency নির্বাচন করা যাবে।
-   Pricing Update Date দেখাতে হবে।

------------------------------------------------------------------------

# 15. Multi-Device Gateway

একই Gateway একাধিক Device ও Application ব্যবহার করতে পারবে।

## 15.1 Device Registration

প্রতিটি Device-এর জন্য:

-   Device Name
-   Device Type
-   Device ID
-   Access Token
-   Permissions
-   Created Date
-   Last Active
-   Status

## 15.2 Example Clients

-   JH GetWay Android
-   JH One AI
-   JUNIOR AI
-   OpenCode
-   Codex
-   Development Computer
-   Other API Applications

## 15.3 Device Actions

-   Add Device
-   Rename
-   View Details
-   Regenerate Token
-   Revoke Access
-   View Usage
-   Enable/Disable

প্রতিটি Device-এর Token আলাদা হবে। একটি Device revoke করলে অন্য Device-এর
Access বন্ধ হবে না।

## 15.4 Connection Types

-   Local Network
-   Remote Server
-   Secure Tunnel (যদি configured থাকে)

Remote Gateway ব্যবহারের সময় HTTPS বাধ্যতামূলক।

------------------------------------------------------------------------

# 16. API Key Vault

API Key Vault হলো Provider Credentials-এর নিরাপদ Storage।

## 16.1 Key Management

-   Add Key
-   Edit
-   Delete
-   Rename
-   Test
-   Rotate
-   Enable/Disable
-   Set Default Key
-   View Usage

## 16.2 Multiple Keys

একই Provider-এর একাধিক API Key রাখা যাবে।

উদাহরণ:

``` text
Gemini
  - Gemini Key 1
  - Gemini Key 2
  - Gemini Key 3
```

Key Selection Policy:

-   Manual
-   Priority
-   Round Robin (যেখানে Provider Terms অনুমোদন করে)
-   Failure-Based Alternate Key

Provider-এর Rate Limit বা Account Restriction এড়িয়ে যাওয়ার জন্য Key
Rotation ব্যবহার করা যাবে না। কেবল অনুমোদিত Key Management ও Availability
Policy অনুযায়ী ব্যবহার করতে হবে।

## 16.3 Security

-   Encrypted Storage
-   Masked Display
-   No Plain Text Logs
-   No Secret in Analytics
-   Secure Backup
-   Explicit Delete Confirmation
-   Key Access Audit

Key-এর Status:

-   Active
-   Invalid
-   Expired
-   Rate Limited
-   Disabled
-   Unknown

------------------------------------------------------------------------

# 17. Activity & Logs

একটি Central Activity Screen থাকবে।

## 17.1 Activity Categories

-   Gateway Request
-   Provider Connection
-   Provider Error
-   Fallback Event
-   Device Connection
-   Key Update
-   MCP Tool Execution
-   Workflow Execution
-   Authentication Event
-   System Event

## 17.2 Log Details

-   Event ID
-   Event Type
-   Timestamp
-   Related Provider/Device/Workflow
-   Status
-   Safe Error Message

## 17.3 Log Controls

-   Search
-   Filter
-   Date Range
-   Clear Logs
-   Export Logs

Sensitive Data Log করা যাবে না। Log Retention Configurable হতে হবে।

------------------------------------------------------------------------

# 18. Settings

Settings Screen-এ নিচের Section থাকবে।

## General

-   Application Name
-   Language
-   Theme
-   Default Model
-   Default Provider
-   Startup Preferences

## Gateway

-   Gateway Status
-   Gateway Address
-   Port (যেখানে প্রযোজ্য)
-   Authentication
-   Timeout
-   Rate Limit
-   Streaming

## Routing

-   Default Routing Mode
-   Fallback
-   Retry
-   Provider Priority

## Security

-   App Lock
-   Session
-   API Token
-   Credential Security
-   Backup Encryption

## Notifications

-   Provider Failure
-   Gateway Disconnected
-   Rate Limit
-   Workflow Completed
-   Security Event

## Data & Backup

-   Export Configuration
-   Import Configuration
-   Backup
-   Restore
-   Clear Local Data

## About

-   JH GetWay
-   Version
-   Build Number
-   Copyright
-   Privacy Policy
-   Terms
-   Open Source Licenses
-   JH Soft Corporation

------------------------------------------------------------------------

# 19. Onboarding & First-Time Setup

প্রথমবার Application চালু হলে সহজ Setup Wizard দেখাতে হবে।

## Step 1: Welcome

-   JH GetWay Branding
-   বাংলা/English নির্বাচন
-   Light/Dark/System Theme নির্বাচন

## Step 2: Gateway Mode

-   Local Gateway
-   Remote Gateway
-   Connect to Existing Gateway

## Step 3: Provider Setup

-   Add First Provider
-   অথবা Skip

## Step 4: API Key Setup

-   Securely Add API Key
-   অথবা Skip

## Step 5: Connection Test

-   Gateway Test
-   Provider Test

## Step 6: Dashboard

-   Setup Summary
-   Gateway Status
-   Next Action

কোনো Provider API Key না থাকলে Application ব্যবহার করা যাবে না---এমন
বাধ্যবাধকতা থাকবে না। User পরে Provider যুক্ত করতে পারবে।

------------------------------------------------------------------------

# 20. System Behavior: End-to-End Examples

## Example A: Normal AI Request

1.  OpenCode Gateway Address ব্যবহার করে Request পাঠায়।
2.  Gateway Token যাচাই করে।
3.  Model Alias শনাক্ত করে।
4.  Router Provider নির্বাচন করে।
5.  Provider Adapter Request পাঠায়।
6.  Response গ্রহণ করে।
7.  Client-কে Streaming বা Normal Response দেয়।
8.  Usage Record তৈরি করে।

## Example B: Provider Failure

1.  Gemini-তে Request পাঠানো হলো।
2.  Provider Rate Limit Response দিল।
3.  Router Error Category যাচাই করল।
4.  Fallback Policy পরীক্ষা করল।
5.  অনুমোদিত Backup Provider নির্বাচন করল।
6.  Retry করা নিরাপদ হলে Request পাঠাল।
7.  Response Client-কে দিল।
8.  Analytics-এ Fallback Event সংরক্ষণ করল।

## Example C: Local AI

1.  User Local AI Server যুক্ত করল।
2.  Connection Test সফল হলো।
3.  Model List পাওয়া গেল।
4.  User Local Model নির্বাচন করল।
5.  Gateway Request Local Model-এ পাঠাল।
6.  Response ফেরত দিল।
7.  Local Usage ও Latency Record করল।

## Example D: MCP Tool

1.  User MCP Server যুক্ত করল।
2.  Gateway Server-এর সঙ্গে Connect করল।
3.  Tools Discover করল।
4.  User Tool Permission নির্ধারণ করল।
5.  AI Request Tool ব্যবহারের প্রয়োজন জানাল।
6.  Permission যাচাই হলো।
7.  প্রয়োজন হলে User Approval নেওয়া হলো।
8.  Tool Execute হলো।
9.  Result AI Model-এ ফেরত গেল।

## Example E: Workflow

1.  User Workflow তৈরি করল।
2.  Input ও Model Node যুক্ত করল।
3.  Condition ও Output Node যুক্ত করল।
4.  Workflow Validate করল।
5.  User Run করল।
6.  Execution Engine Node অনুযায়ী কাজ করল।
7.  প্রতিটি Node-এর Status সংরক্ষণ করল।
8.  Final Output দেখাল।

------------------------------------------------------------------------

# 21. Important Status & Empty States

প্রতিটি Feature-এর জন্য নিচের State থাকতে হবে:

-   Loading
-   Loaded
-   Empty
-   Error
-   Offline
-   Disabled
-   Permission Required
-   Connecting
-   Success

উদাহরণ:

**No Provider Added**

বাংলা: এখনো কোনো AI Provider যুক্ত করা হয়নি। প্রথম Provider যুক্ত করে শুরু করুন।

English: No AI provider has been added yet. Add your first provider to
get started.

Empty State-এ প্রাসঙ্গিক Action Button থাকতে হবে।

------------------------------------------------------------------------

# 22. Privacy & Security Requirements

-   API Key কখনো UI বা Log-এ সম্পূর্ণ প্রকাশ করা যাবে না।
-   Credential নিরাপদ Encryption ছাড়া সংরক্ষণ করা যাবে না।
-   Gateway Token আলাদা করে তৈরি ও Revoke করা যাবে।
-   User-এর অনুমতি ছাড়া Request Content সংরক্ষণ করা যাবে না।
-   Local AI Server-এ Credential পাঠানোর আগে Host যাচাই করতে হবে।
-   MCP Tool Permission বাধ্যতামূলক।
-   Destructive Action-এ Confirmation থাকতে হবে।
-   Remote Connection-এ HTTPS ব্যবহার করতে হবে।
-   Sensitive Data Backup-এ Encryption থাকতে হবে।
-   Authentication Failure ও Security Event Audit Log-এ রাখতে হবে।
-   Secret, Password ও Token কখনো Source Code-এ Hardcode করা যাবে না।

------------------------------------------------------------------------

# 23. Version 1.0 Feature Checklist

নিচের সব Feature Version 1.0-এর Scope-এর মধ্যে থাকবে।

## Core

-   [ ] Dashboard
-   [ ] Provider Management
-   [ ] Model Management
-   [ ] Universal AI Endpoint
-   [ ] Streaming
-   [ ] Smart Router
-   [ ] Fallback
-   [ ] API Key Vault

## Coding & Local AI

-   [ ] Coding Agent Gateway
-   [ ] OpenCode Integration
-   [ ] Codex Compatibility
-   [ ] Local AI Bridge
-   [ ] Ollama Integration
-   [ ] llama.cpp Integration

## Advanced AI

-   [ ] MCP Server Manager
-   [ ] Tool Discovery
-   [ ] Tool Permission
-   [ ] AI Workflow Builder
-   [ ] Workflow Execution
-   [ ] Workflow History

## Developer Tools

-   [ ] AI Playground
-   [ ] Multi-Model Compare
-   [ ] Request Inspector
-   [ ] API Usage Analytics
-   [ ] Latency Monitoring
-   [ ] Error Monitoring

## Connectivity

-   [ ] Multi-Device Management
-   [ ] Device Tokens
-   [ ] Local Gateway
-   [ ] Remote Gateway
-   [ ] Connection Monitoring

## Experience

-   [ ] Bengali Language
-   [ ] English Language
-   [ ] Light Mode
-   [ ] Dark Mode
-   [ ] System Theme
-   [ ] Onboarding
-   [ ] Notifications
-   [ ] Backup & Restore
-   [ ] Activity Logs

------------------------------------------------------------------------

# 24. AI Studio Build Instructions

এই Document Google AI Studio-তে JH GetWay নতুন করে তৈরি করার Master
Product Specification হিসেবে ব্যবহার করতে হবে।

## Build Rules

1.  Product-এর নাম সব জায়গায় JH GetWay থাকবে।
2.  Organization/Brand হিসেবে JH Soft Corporation থাকবে।
3.  Application-এর UI হবে Simple, Professional ও Mobile-First।
4.  বাংলা ও English সম্পূর্ণ সমর্থন করতে হবে।
5.  Light, Dark ও System Theme থাকতে হবে।
6.  Navigation ও Feature-এর মধ্যে কার্যকর সংযোগ থাকতে হবে।
7.  কোনো Fake API Success, Fake Provider Status বা Fake Analytics তৈরি
    করা যাবে না।
8.  বাস্তব Backend Integration ছাড়া কোনো Feature-কে Complete দেখানো যাবে
    না।
9.  API Key নিরাপদভাবে সংরক্ষণ করতে হবে।
10. সব Form-এ Validation ও Error Handling থাকতে হবে।
11. প্রতিটি Screen-এ Loading, Empty, Success ও Error State থাকতে হবে।
12. Responsive Layout বজায় রাখতে হবে।
13. সব গুরুত্বপূর্ণ Action বাস্তবে কাজ করতে হবে।
14. কোনো Button শুধু Decoration হিসেবে রাখা যাবে না।
15. Existing কোনো অন্য Product বা Brand-এর নাম ব্যবহার করা যাবে না।
16. Application-এর সব Screen-এ একই Design System অনুসরণ করতে হবে।
17. User-এর অনুমতি ছাড়া Sensitive Data সংরক্ষণ করা যাবে না।
18. Feature অসম্পূর্ণ থাকলে সেটিকে স্পষ্টভাবে Unavailable বা Not Configured
    দেখাতে হবে।

## Recommended Build Order in AI Studio

একবারে সব Feature তৈরি করার পরিবর্তে নিচের ক্রমে Build ও Verify করতে হবে:

1.  Application Shell, Navigation ও Design System
2.  Language ও Theme System
3.  Dashboard ও Onboarding
4.  Provider ও Model Management
5.  API Key Vault
6.  Gateway ও API Integration
7.  Smart Router ও Fallback
8.  Coding Agent Gateway
9.  Local AI Bridge
10. MCP Server Manager
11. Workflow Engine
12. Playground
13. Analytics ও Logs
14. Multi-Device Management
15. Security, Backup ও Final Testing

প্রতিটি ধাপের কাজ শেষ হলে বাস্তবে Test করে পরবর্তী ধাপে যেতে হবে।

------------------------------------------------------------------------

# 25. Final Product Vision

JH GetWay এমন একটি কেন্দ্রীয় AI Platform হবে যেখানে একজন ব্যবহারকারী:

-   নিজের সব AI Provider পরিচালনা করতে পারবে।
-   একটি Gateway Endpoint দিয়ে বিভিন্ন AI Model ব্যবহার করতে পারবে।
-   প্রয়োজন অনুযায়ী Provider ও Model পরিবর্তন করতে পারবে।
-   Provider Failure হলে Automatic Fallback পাবে।
-   Coding Agent-কে একই Gateway-তে যুক্ত করতে পারবে।
-   Local AI ও Cloud AI একসঙ্গে ব্যবহার করতে পারবে।
-   MCP Server ও Tools পরিচালনা করতে পারবে।
-   Visual AI Workflow তৈরি করতে পারবে।
-   Model Compare ও Test করতে পারবে।
-   Token, Usage, Latency ও Cost পর্যবেক্ষণ করতে পারবে।
-   একাধিক Device থেকে Gateway ব্যবহার করতে পারবে।
-   বাংলা ও English এবং Light ও Dark Mode-এ Application ব্যবহার করতে
    পারবে।

**JH GetWay --- One Gateway. Complete AI Infrastructure.**

**Copyright © 2026 JH Soft Corporation. All Rights Reserved.**
