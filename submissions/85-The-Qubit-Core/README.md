# DocuScan AI — Enterprise OCR Document Summarization Engine

> **National Hackathon 2026 — Problem Statement P2**
> Built with Java Spring Boot + Tesseract OCR + React + Tailwind CSS

---

## 🏗️ Architecture Overview

```
OCR/
├── backend/                        # Java Spring Boot 3.2 (Port 8080)
│   └── src/main/java/com/ocrengine/app/
│       ├── controller/             # REST endpoints
│       ├── service/
│       │   ├── TesseractOcrService.java      # Tesseract OCR engine
│       │   ├── EntityExtractionService.java  # NLP entity extraction (no AI API)
│       │   ├── SummarizationService.java     # TextRank summarization (no AI API)
│       │   └── DocumentProcessingService.java# Orchestration
│       ├── model/                  # Domain models
│       ├── dto/                    # Request/response DTOs
│       ├── config/                 # CORS, OCR configuration
│       ├── exception/              # Global error handling
│       └── util/                   # File validation utils
│
├── frontend/                       # React + Vite + Tailwind CSS (Port 5173)
│   └── src/
│       ├── components/
│       │   ├── Header.jsx              # Top navbar
│       │   ├── UploadZone.jsx          # Drag-and-drop upload
│       │   ├── ProcessingStatus.jsx    # Animated processing UI
│       │   ├── SideBySideViewer.jsx    # OCR verification panel
│       │   ├── EntityCards.jsx         # Key entity highlight cards
│       │   └── SummaryPanel.jsx        # Executive summary
│       ├── api/ocrApi.js           # Axios API client
│       ├── hooks/useOcrProcessor.js# State management hook
│       └── App.jsx                 # Main application
│
├── setup.bat           # One-time setup (tessdata + npm install)
├── start-all.bat       # Launch everything
├── start-backend.bat   # Launch backend only
└── start-frontend.bat  # Launch frontend only
```

---

## ⚙️ Tech Stack (Exactly as Problem Statement Specifies)

| Layer     | Technology |
|-----------|-----------|
| Backend   | **Java 21 + Spring Boot 3.2** |
| OCR Engine | **Tesseract OCR 5.x** via Tess4J library |
| PDF Processing | **Apache PDFBox 3.x** (renders PDF pages at 300 DPI) |
| NLP / Summarization | **Pure Java** — TextRank algorithm + Regex NLP (no AI API) |
| Frontend  | **React 19 + Vite + Tailwind CSS v4** |
| HTTP Client | Axios |
| Icons     | Lucide React |
| Animations | Framer Motion + CSS |

---

## 🚀 How to Run (3 Steps)

### Step 1 — First-time setup (run once)
Double-click **`setup.bat`**

This will:
- Verify Java & Maven are installed
- Download Tesseract `eng.traineddata` (~12MB) automatically
- Run `npm install` for the frontend

### Step 2 — Launch everything
Double-click **`start-all.bat`**

This opens two terminal windows:
- **Backend** on `http://localhost:8080`
- **Frontend** on `http://localhost:5173`

Your browser will open automatically after ~8 seconds.

### Step 3 — Wait for Spring Boot
Spring Boot takes ~20–40 seconds on first launch (Maven downloads dependencies).
When you see `Started OcrEngineApplication` in the backend window, you're ready!

---

## 🔌 API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET`  | `/api/v1/health` | Health check |
| `GET`  | `/api/v1/ocr/info` | Engine capabilities |
| `POST` | `/api/v1/ocr/process` | Upload & process document |

### Process Document Request
```
POST /api/v1/ocr/process
Content-Type: multipart/form-data

file: <your PDF or image file>
```

### Response Example
```json
{
  "success": true,
  "documentId": "uuid-here",
  "fileName": "contract.pdf",
  "rawText": "AGREEMENT made this 1st day of March...",
  "executiveSummary": "This agreement establishes terms between...",
  "entities": [
    { "type": "DUE_DATE",     "value": "March 31, 2026", "confidence": 0.92 },
    { "type": "TOTAL_AMOUNT", "value": "$25,000.00",     "confidence": 0.88 },
    { "type": "SIGNATORY",    "value": "John Smith",     "confidence": 0.85 }
  ],
  "metadata": {
    "pageCount": 3,
    "wordCount": 1842,
    "ocrConfidencePercent": 85.0,
    "processingTimeMs": 4231,
    "fileSize": "1.2 MB"
  }
}
```

---

## 🔍 Key Features Implemented

| Feature | Implementation |
|---------|---------------|
| **Drag-and-Drop Ingestion** | React Dropzone with file type & size validation |
| **Side-by-Side Verification** | Split view: original image + extracted OCR text with zoom |
| **Key Entity Extraction** | Regex NLP: Due Dates, Financial Amounts, Signatories, Organizations, Reference Numbers |
| **Executive Summary** | TextRank extractive summarization — pure Java, no AI API |
| **Multi-page PDF** | PDFBox renders each page at 300 DPI, OCR'd individually |
| **Security** | CORS config, file type validation, size limits, temp file cleanup |
| **Error Handling** | Global exception handler with structured error responses |
| **Scalability** | Stateless REST design, configurable via application.properties |

---

## ⚙️ Configuration

Edit `backend/src/main/resources/application.properties`:

```properties
server.port=8080
ocr.tessdata.path=./tessdata    # Path to Tesseract language data
ocr.language=eng                # OCR language (eng, fra, deu, etc.)
ocr.upload.max-size-mb=20       # Max file size
spring.servlet.multipart.max-file-size=20MB
```

---

## 🛠️ Prerequisites

| Tool | Version | Where |
|------|---------|--------|
| Java JDK | 21 | `C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot` |
| Maven | 3.9.14 | `C:\maven\apache-maven-3.9.14-bin\apache-maven-3.9.14` |
| Node.js | 18+ | Already installed |

---

## 📁 Supported File Types

| Format | Max Size | Notes |
|--------|----------|-------|
| PDF | 20 MB | Scanned (image-based) PDFs — each page OCR'd at 300 DPI |
| PNG | 20 MB | Best quality for OCR |
| JPG / JPEG | 20 MB | Good quality |
| TIFF | 20 MB | Excellent for document scans |
| BMP | 20 MB | Supported |

---

*Built for National Hackathon 2026 — P2: OCR-Driven Enterprise Document Summarization Engine*
