/* PDF Conduit — web frontend logic. Vanilla JS, single IIFE. No external deps.
 *
 * The whole UI is driven by the OPS table below: each operation declares its
 * endpoint, the multipart file part name ("files" = multi, "file" = single),
 * and a list of option fields. Rendering, value collection and the request body
 * are all derived from that data, so adding an operation is a data change here
 * plus (server side) a new endpoint — no bespoke UI code.
 */
(function () {
  "use strict";

  var API = "/api";

  /* Field-type note:
   *   text | password | number | select | checkbox | file
   * Each field's `name` MUST equal the multipart part name in the API contract.
   */
  var OPS = [
    {
      id: "merge", label: "Merge", endpoint: "/merge", filePart: "files",
      desc: "Combine several PDFs, images or office documents into one PDF.",
      accept: ".pdf,image/*,.docx,.odt,.rtf,.xlsx,.pptx,.txt",
      reorder: true,
      fields: [
        { name: "outputName", label: "Output file name", type: "text",
          placeholder: "merged.pdf", help: "Optional. Name for the combined PDF." }
      ]
    },
    {
      id: "extract", label: "Extract", endpoint: "/extract", filePart: "file",
      desc: "Extract a page range from a PDF, optionally as separate files.",
      fields: [
        { name: "pages", label: "Pages", type: "text", placeholder: "1,3,5-8 (default: all)",
          help: "Ranges like 1, 2-5, 1,3,5-8, end-2. Empty = all pages." },
        { name: "separate", label: "Split into a separate PDF per page (ZIP)", type: "checkbox" }
      ]
    },
    {
      id: "compress", label: "Compress", endpoint: "/compress", filePart: "files",
      desc: "Shrink PDFs towards a target size.",
      showCompressStats: true,
      fields: [
        { name: "targetSize", label: "Target size", type: "text", required: true,
          placeholder: "5MB", help: "e.g. 500KB, 5MB, 1.5MB — or a raw byte count." }
      ]
    },
    {
      id: "rotate", label: "Rotate", endpoint: "/rotate", filePart: "files",
      desc: "Rotate pages by a fixed angle.",
      fields: [
        { name: "angle", label: "Angle", type: "select", required: true, value: "90",
          options: [["90", "90° clockwise"], ["180", "180°"], ["270", "270° (90° counter-clockwise)"]] },
        { name: "pages", label: "Pages", type: "text", placeholder: "1,3,5-8 (default: all)",
          help: "Ranges like 1, 2-5, 1,3,5-8, end-2. Empty = all pages." }
      ]
    },
    {
      id: "arrange", label: "Arrange", endpoint: "/arrange", filePart: "file",
      desc: "Reorder, reverse or duplicate pages within one PDF.",
      fields: [
        { name: "order", label: "Page order", type: "text", required: true, placeholder: "3,1,2",
          help: "e.g. 3,1,2 to reorder, 5-1 to reverse, repeats to duplicate." }
      ]
    },
    {
      id: "to-pdf", label: "To PDF", endpoint: "/to-pdf", filePart: "files",
      desc: "Convert each image or office document to its own PDF.",
      accept: "image/*,.docx,.odt,.rtf,.xlsx,.pptx,.txt",
      fields: [
        { name: "pageSize", label: "Page size", type: "select", value: "FIT",
          options: [["FIT", "Fit to content"], ["A4", "A4"], ["A3", "A3"], ["LETTER", "Letter"]] }
      ]
    },
    {
      id: "protect", label: "Protect", endpoint: "/protect", filePart: "files",
      desc: "Encrypt PDFs with a password (AES-128).",
      fields: [
        { name: "userPassword", label: "User password", type: "password", required: true,
          help: "Required to open the document." },
        { name: "ownerPassword", label: "Owner password", type: "password",
          help: "Optional. Controls permissions; defaults to the user password." }
      ]
    },
    {
      id: "unlock", label: "Unlock", endpoint: "/unlock", filePart: "files",
      desc: "Remove the password from protected PDFs.",
      fields: [
        { name: "password", label: "Password", type: "password", required: true,
          help: "The current document password." }
      ]
    },
    {
      id: "metadata", label: "Metadata", endpoint: "/metadata", filePart: "file",
      desc: "View and edit document information.",
      metadata: true,
      fields: [
        { name: "title", label: "Title", type: "text" },
        { name: "author", label: "Author", type: "text" },
        { name: "subject", label: "Subject", type: "text" },
        { name: "keywords", label: "Keywords", type: "text", placeholder: "comma, separated" },
        { name: "strip", label: "Strip all metadata (ignore the fields above)", type: "checkbox" }
      ]
    },
    {
      id: "watermark", label: "Watermark", endpoint: "/watermark", filePart: "files",
      desc: "Stamp a text or image watermark across every page.",
      fields: [
        { name: "text", label: "Watermark text", type: "text", placeholder: "CONFIDENTIAL",
          help: "Provide either text OR an image (below), not both." },
        { name: "image", label: "Watermark image", type: "file", accept: "image/*" },
        { name: "opacity", label: "Opacity", type: "number", value: "0.3", min: "0", max: "1", step: "0.05" },
        { name: "rotation", label: "Rotation (°)", type: "number", value: "45", min: "0", max: "360", step: "1" },
        { name: "scale", label: "Scale", type: "number", value: "1", min: "0.1", max: "5", step: "0.1" }
      ]
    },
    {
      id: "redact", label: "Redact", endpoint: "/redact", filePart: "file",
      desc: "Permanently remove content in rectangular regions (rasterises affected pages).",
      redact: true,
      fields: [
        { name: "dpi", label: "Raster DPI", type: "number", value: "150", min: "72", max: "600", step: "1",
          help: "Resolution used when flattening redacted pages." }
      ]
    },
    {
      id: "to-images", label: "To Images", endpoint: "/to-images", filePart: "file",
      desc: "Render pages to image files (ZIP).",
      fields: [
        { name: "format", label: "Format", type: "select", value: "PNG",
          options: [["PNG", "PNG"], ["JPG", "JPG"]] },
        { name: "dpi", label: "DPI", type: "number", value: "150", min: "72", max: "600", step: "1" },
        { name: "pages", label: "Pages", type: "text", placeholder: "1,3,5-8 (default: all)",
          help: "Empty = all pages." }
      ]
    },
    {
      id: "to-text", label: "To Text", endpoint: "/to-text", filePart: "file",
      desc: "Extract the text content of a PDF.",
      fields: [
        { name: "format", label: "Format", type: "select", value: "TXT",
          options: [["TXT", "Plain text (.txt)"]] },
        { name: "pages", label: "Pages", type: "text", placeholder: "1,3,5-8 (default: all)",
          help: "Empty = all pages." }
      ]
    }
  ];

  /* ---- state ---- */
  var state = {
    op: OPS[0],
    files: [],       // File[]
    regions: []      // {pageIndex,x,y,width,height}
  };

  /* ---- element refs ---- */
  var $ = function (id) { return document.getElementById(id); };
  var el = {
    opList: $("op-list"),
    title: $("op-title"),
    desc: $("op-desc"),
    dropzone: $("dropzone"),
    dzHint: $("dz-hint"),
    fileInput: $("file-input"),
    fileList: $("file-list"),
    form: $("op-form"),
    regionsBlock: $("regions-block"),
    runBtn: $("run-btn"),
    readMetaBtn: $("read-meta-btn"),
    spinner: $("spinner"),
    result: $("result"),
    error: $("error"),
    healthDot: $("health-dot"),
    themeToggle: $("theme-toggle")
  };

  /* ---- theming ---- */
  function initTheme() {
    var saved = null;
    try { saved = localStorage.getItem("pdfconduit-theme"); } catch (e) {}
    if (saved === "light" || saved === "dark") {
      document.documentElement.setAttribute("data-theme", saved);
    }
    el.themeToggle.addEventListener("click", function () {
      var cur = document.documentElement.getAttribute("data-theme");
      if (!cur) {
        // resolve current effective theme, then flip
        cur = window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
      }
      var next = cur === "dark" ? "light" : "dark";
      document.documentElement.setAttribute("data-theme", next);
      try { localStorage.setItem("pdfconduit-theme", next); } catch (e) {}
    });
  }

  /* ---- helpers ---- */
  function fmtBytes(n) {
    n = Number(n);
    if (!isFinite(n)) return "";
    var u = ["B", "KB", "MB", "GB"], i = 0;
    while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
    return (i === 0 ? n : n.toFixed(1)) + " " + u[i];
  }
  function clearNode(node) { while (node.firstChild) node.removeChild(node.firstChild); }
  function iconFor() {
    var s = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    s.setAttribute("viewBox", "0 0 24 24");
    s.setAttribute("class", "op-ico");
    s.innerHTML = '<path d="M6 3h8l4 4v14H6z" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/>' +
      '<path d="M14 3v4h4" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/>';
    return s;
  }

  /* ---- sidebar ---- */
  function buildSidebar() {
    OPS.forEach(function (op) {
      var li = document.createElement("li");
      li.setAttribute("role", "presentation");
      var b = document.createElement("button");
      b.type = "button";
      b.className = "op-btn";
      b.setAttribute("role", "tab");
      b.dataset.op = op.id;
      b.appendChild(iconFor());
      var span = document.createElement("span");
      span.textContent = op.label;
      b.appendChild(span);
      b.addEventListener("click", function () { selectOp(op); });
      li.appendChild(b);
      el.opList.appendChild(li);
    });
  }

  function selectOp(op) {
    state.op = op;
    state.regions = [];
    Array.prototype.forEach.call(el.opList.querySelectorAll(".op-btn"), function (b) {
      var active = b.dataset.op === op.id;
      b.classList.toggle("active", active);
      b.setAttribute("aria-selected", active ? "true" : "false");
    });
    el.title.textContent = op.label;
    el.desc.textContent = op.desc || "";
    hide(el.result); hide(el.error);
    renderFileControls();
    renderForm();
    renderRegions();
    el.readMetaBtn.hidden = !op.metadata;
    el.runBtn.textContent = op.metadata ? "Save metadata" : "Run";
  }

  /* ---- file controls ---- */
  function renderFileControls() {
    var op = state.op;
    var multi = op.filePart === "files";
    el.fileInput.multiple = multi;
    el.fileInput.accept = op.accept || ".pdf";
    el.dzHint.textContent = multi
      ? "Accepts one or more files."
      : "Single file only.";
    renderFileList();
  }

  function addFiles(fileList) {
    var incoming = Array.prototype.slice.call(fileList);
    if (!incoming.length) return;
    if (state.op.filePart === "file") {
      state.files = [incoming[0]];           // single-file op: keep just one
    } else {
      state.files = state.files.concat(incoming);
    }
    renderFileList();
  }

  function renderFileList() {
    clearNode(el.fileList);
    state.files.forEach(function (f, idx) {
      var li = document.createElement("li");
      li.className = "file-row";

      var name = document.createElement("span");
      name.className = "fname";
      name.textContent = f.name;
      li.appendChild(name);

      var size = document.createElement("span");
      size.className = "fsize";
      size.textContent = fmtBytes(f.size);
      li.appendChild(size);

      if (state.op.reorder && state.files.length > 1) {
        var re = document.createElement("span");
        re.className = "reorder";
        re.appendChild(moveBtn("↑", "Move up", idx === 0, function () { move(idx, -1); }));
        re.appendChild(moveBtn("↓", "Move down", idx === state.files.length - 1, function () { move(idx, 1); }));
        li.appendChild(re);
      }

      var rm = document.createElement("button");
      rm.type = "button";
      rm.className = "mini-btn remove";
      rm.textContent = "✕";
      rm.setAttribute("aria-label", "Remove " + f.name);
      rm.addEventListener("click", function () {
        state.files.splice(idx, 1);
        renderFileList();
      });
      li.appendChild(rm);

      el.fileList.appendChild(li);
    });
  }

  function moveBtn(sym, label, disabled, fn) {
    var b = document.createElement("button");
    b.type = "button";
    b.className = "mini-btn";
    b.textContent = sym;
    b.setAttribute("aria-label", label);
    b.disabled = disabled;
    if (!disabled) b.addEventListener("click", fn);
    return b;
  }
  function move(idx, dir) {
    var j = idx + dir;
    if (j < 0 || j >= state.files.length) return;
    var tmp = state.files[idx];
    state.files[idx] = state.files[j];
    state.files[j] = tmp;
    renderFileList();
  }

  /* ---- dynamic form ---- */
  function renderForm() {
    clearNode(el.form);
    var op = state.op;
    var simple = [], grid = [];
    op.fields.forEach(function (f) {
      // group short numeric/select fields into a responsive grid
      (f.type === "number" || f.type === "select" || f.type === "checkbox" ? grid : simple).push(f);
    });
    simple.forEach(function (f) { el.form.appendChild(fieldNode(f)); });
    if (grid.length) {
      var g = document.createElement("div");
      g.className = "form-grid";
      grid.forEach(function (f) {
        if (f.type === "checkbox") { el.form.appendChild(fieldNode(f)); }
        else { g.appendChild(fieldNode(f)); }
      });
      if (g.childNodes.length) el.form.appendChild(g);
    }
  }

  function fieldNode(f) {
    var wrap = document.createElement("div");
    wrap.className = "field" + (f.type === "checkbox" ? " checkbox" : "");
    var input;
    var inputId = "f_" + state.op.id + "_" + f.name;

    if (f.type === "select") {
      input = document.createElement("select");
      (f.options || []).forEach(function (o) {
        var opt = document.createElement("option");
        opt.value = o[0]; opt.textContent = o[1];
        input.appendChild(opt);
      });
      if (f.value) input.value = f.value;
    } else if (f.type === "checkbox") {
      input = document.createElement("input");
      input.type = "checkbox";
    } else {
      input = document.createElement("input");
      input.type = (f.type === "password") ? "password" :
                   (f.type === "number") ? "number" :
                   (f.type === "file") ? "file" : "text";
      if (f.placeholder) input.placeholder = f.placeholder;
      if (f.value != null) input.value = f.value;
      if (f.min != null) input.min = f.min;
      if (f.max != null) input.max = f.max;
      if (f.step != null) input.step = f.step;
      if (f.accept) input.accept = f.accept;
    }
    input.id = inputId;
    input.name = f.name;
    input.dataset.fname = f.name;
    input.dataset.ftype = f.type;

    var label = document.createElement("label");
    label.setAttribute("for", inputId);
    label.textContent = f.label + (f.required ? " *" : "");

    if (f.type === "checkbox") {
      wrap.appendChild(input);
      wrap.appendChild(label);
    } else {
      wrap.appendChild(label);
      wrap.appendChild(input);
    }
    if (f.help) {
      var help = document.createElement("small");
      help.className = "help";
      help.textContent = f.help;
      wrap.appendChild(help);
    }
    return wrap;
  }

  /* ---- redact regions ---- */
  function renderRegions() {
    var block = el.regionsBlock;
    clearNode(block);
    if (!state.op.redact) { block.hidden = true; return; }
    block.hidden = false;

    var h = document.createElement("h2");
    h.textContent = "Redaction regions (advanced)";
    block.appendChild(h);

    var note = document.createElement("p");
    note.className = "adv-note";
    note.textContent = "Coordinates are in PDF points. Origin is the top-left of the page; " +
      "page index is 0-based. Add one or more rectangles to redact.";
    block.appendChild(note);

    var row = document.createElement("div");
    row.className = "region-inputs";
    var defs = [
      ["pageIndex", "Page (0-based)", "0"],
      ["x", "X", "0"],
      ["y", "Y", "0"],
      ["width", "Width", "100"],
      ["height", "Height", "20"]
    ];
    var inputs = {};
    defs.forEach(function (d) {
      var fw = document.createElement("div");
      fw.className = "field";
      var lab = document.createElement("label");
      var iid = "rg_" + d[0];
      lab.setAttribute("for", iid);
      lab.textContent = d[1];
      var inp = document.createElement("input");
      inp.type = "number"; inp.id = iid; inp.value = d[2];
      inp.min = "0"; inp.step = "1";
      inputs[d[0]] = inp;
      fw.appendChild(lab); fw.appendChild(inp);
      row.appendChild(fw);
    });
    var add = document.createElement("button");
    add.type = "button";
    add.className = "btn btn-ghost";
    add.textContent = "Add";
    add.addEventListener("click", function () {
      var r = {
        pageIndex: parseInt(inputs.pageIndex.value, 10) || 0,
        x: Number(inputs.x.value) || 0,
        y: Number(inputs.y.value) || 0,
        width: Number(inputs.width.value) || 0,
        height: Number(inputs.height.value) || 0
      };
      if (r.width <= 0 || r.height <= 0) { flashError("Region width and height must be greater than 0."); return; }
      state.regions.push(r);
      renderRegionList(listEl);
    });
    row.appendChild(add);
    block.appendChild(row);

    var listEl = document.createElement("ul");
    listEl.className = "region-list";
    block.appendChild(listEl);
    renderRegionList(listEl);
  }

  function renderRegionList(listEl) {
    clearNode(listEl);
    state.regions.forEach(function (r, idx) {
      var li = document.createElement("li");
      var s = document.createElement("span");
      s.textContent = "p" + r.pageIndex + "  x=" + r.x + " y=" + r.y + " w=" + r.width + " h=" + r.height;
      li.appendChild(s);
      var rm = document.createElement("button");
      rm.type = "button"; rm.className = "mini-btn remove"; rm.textContent = "✕";
      rm.setAttribute("aria-label", "Remove region " + (idx + 1));
      rm.addEventListener("click", function () { state.regions.splice(idx, 1); renderRegionList(listEl); });
      li.appendChild(rm);
      listEl.appendChild(li);
    });
  }

  /* ---- collect form values into FormData ---- */
  function buildFormData(op) {
    var fd = new FormData();
    // files
    if (op.filePart === "files") {
      state.files.forEach(function (f) { fd.append("files", f); });
    } else {
      fd.append("file", state.files[0]);
    }
    // fields
    op.fields.forEach(function (f) {
      var input = el.form.querySelector('[data-fname="' + f.name + '"]');
      if (!input) return;
      if (f.type === "checkbox") {
        if (input.checked) fd.append(f.name, "true");
      } else if (f.type === "file") {
        if (input.files && input.files[0]) fd.append(f.name, input.files[0]);
      } else {
        var v = input.value != null ? input.value.trim() : "";
        if (v !== "") fd.append(f.name, v);
      }
    });
    // redact regions
    if (op.redact) {
      fd.append("regions", JSON.stringify(state.regions));
    }
    return fd;
  }

  /* ---- validation ---- */
  function validate(op) {
    if (!state.files.length) return "Please add at least one file.";
    var missing = null;
    op.fields.forEach(function (f) {
      if (missing || !f.required) return;
      var input = el.form.querySelector('[data-fname="' + f.name + '"]');
      if (!input) return;
      if (f.type === "checkbox") { if (!input.checked) missing = f.label; }
      else if (!input.value || !input.value.trim()) missing = f.label;
    });
    if (missing) return missing + " is required.";
    if (op.redact && state.regions.length === 0) return "Add at least one redaction region.";
    if (op.id === "watermark") {
      var text = el.form.querySelector('[data-fname="text"]');
      var img = el.form.querySelector('[data-fname="image"]');
      var hasText = text && text.value.trim() !== "";
      var hasImg = img && img.files && img.files.length > 0;
      if (!hasText && !hasImg) return "Provide watermark text or an image.";
      if (hasText && hasImg) return "Provide either text or an image, not both.";
    }
    return null;
  }

  /* ---- run ---- */
  function busy(on) {
    el.runBtn.disabled = on;
    el.readMetaBtn.disabled = on;
    el.spinner.hidden = !on;
  }
  function hide(node) { node.hidden = true; }
  function flashError(msg, title) {
    clearNode(el.error);
    var strong = document.createElement("strong");
    strong.textContent = title || "Error";
    el.error.appendChild(strong);
    el.error.appendChild(document.createTextNode(msg));
    el.error.hidden = false;
    hide(el.result);
  }

  function filenameFromDisposition(res, fallback) {
    var cd = res.headers.get("Content-Disposition") || "";
    var m = /filename\*=UTF-8''([^;]+)/i.exec(cd);
    if (m) { try { return decodeURIComponent(m[1]); } catch (e) { return m[1]; } }
    m = /filename="?([^";]+)"?/i.exec(cd);
    if (m) return m[1];
    return fallback;
  }

  async function run() {
    var op = state.op;
    hide(el.error); hide(el.result);
    var v = validate(op);
    if (v) { flashError(v, "Cannot run"); return; }

    busy(true);
    try {
      var res = await fetch(API + op.endpoint, { method: "POST", body: buildFormData(op) });
      if (!res.ok) { await handleErrorResponse(res); return; }
      var blob = await res.blob();
      var name = filenameFromDisposition(res, op.id + "-result");
      showResult(op, res, blob, name);
    } catch (e) {
      flashError("Network error: " + (e && e.message ? e.message : e) +
        ". Is the server running?", "Request failed");
    } finally {
      busy(false);
    }
  }

  async function handleErrorResponse(res) {
    var msg = "The operation failed (HTTP " + res.status + ").";
    var title = "Error " + res.status;
    try {
      var ct = res.headers.get("Content-Type") || "";
      if (ct.indexOf("application/json") !== -1) {
        var j = await res.json();
        if (j && j.error) msg = j.error;
        if (j && j.code) title = j.code.replace(/_/g, " ");
      } else {
        var t = await res.text();
        if (t) msg = t;
      }
    } catch (e) { /* keep default */ }
    flashError(msg, title);
  }

  function showResult(op, res, blob, name) {
    var url = URL.createObjectURL(blob);
    clearNode(el.result);

    var h = document.createElement("h3");
    h.textContent = "Done";
    el.result.appendChild(h);

    var p = document.createElement("p");
    p.textContent = "Result ready: " + name + " (" + fmtBytes(blob.size) + ").";
    el.result.appendChild(p);

    var a = document.createElement("a");
    a.className = "btn btn-primary dl-btn";
    a.href = url;
    a.download = name;
    a.textContent = "Download";
    el.result.appendChild(a);
    // auto-trigger download for convenience
    a.click();

    // compress stats
    if (op.showCompressStats) {
      var orig = res.headers.get("X-Original-Bytes");
      var result = res.headers.get("X-Result-Bytes");
      var reached = res.headers.get("X-Target-Reached");
      var meta = document.createElement("div");
      meta.className = "meta";
      if (orig || result) {
        meta.innerHTML = "Original: <strong>" + fmtBytes(orig) + "</strong> → " +
          "Result: <strong>" + fmtBytes(result) + "</strong>";
        if (orig && result && Number(orig) > 0) {
          var pct = Math.round((1 - Number(result) / Number(orig)) * 100);
          meta.innerHTML += " (" + pct + "% smaller)";
        }
      }
      if (reached != null) {
        var badge = document.createElement("span");
        var ok = reached === "true";
        badge.className = "badge " + (ok ? "ok" : "warn");
        badge.textContent = ok ? "target reached" : "target not reached";
        meta.appendChild(document.createTextNode(" "));
        meta.appendChild(badge);
      }
      el.result.appendChild(meta);
    }

    // batch failures
    var failures = res.headers.get("X-Batch-Failures");
    if (failures && failures !== "0") {
      var warn = document.createElement("div");
      warn.className = "meta";
      warn.innerHTML = "<span class=\"badge warn\">" + failures +
        " file(s) failed</span> — successful outputs are included; see _failures.txt in the ZIP.";
      el.result.appendChild(warn);
    }

    el.result.hidden = false;
    // revoke later
    setTimeout(function () { URL.revokeObjectURL(url); }, 60000);
  }

  /* ---- metadata read (prefill) ---- */
  async function readMetadata() {
    if (!state.files.length) { flashError("Add a PDF first.", "Cannot read"); return; }
    busy(true);
    hide(el.error); hide(el.result);
    try {
      var fd = new FormData();
      fd.append("file", state.files[0]);
      var res = await fetch(API + "/metadata/read", { method: "POST", body: fd });
      if (!res.ok) { await handleErrorResponse(res); return; }
      var j = await res.json();
      ["title", "author", "subject", "keywords"].forEach(function (k) {
        var input = el.form.querySelector('[data-fname="' + k + '"]');
        if (input) input.value = (j && j[k] != null) ? j[k] : "";
      });
      clearNode(el.result);
      var p = document.createElement("p");
      p.textContent = "Current metadata loaded. Edit the fields and click “Save metadata”.";
      el.result.appendChild(p);
      el.result.hidden = false;
    } catch (e) {
      flashError("Network error: " + (e && e.message ? e.message : e), "Request failed");
    } finally {
      busy(false);
    }
  }

  /* ---- health ---- */
  async function ping() {
    try {
      var res = await fetch(API + "/health", { method: "GET" });
      var up = res.ok;
      el.healthDot.classList.toggle("up", up);
      el.healthDot.classList.toggle("down", !up);
      el.healthDot.title = up ? "Server: up" : "Server: unreachable";
    } catch (e) {
      el.healthDot.classList.add("down");
      el.healthDot.title = "Server: unreachable";
    }
  }

  /* ---- dropzone wiring ---- */
  function wireDropzone() {
    el.dropzone.addEventListener("click", function () { el.fileInput.click(); });
    el.dropzone.addEventListener("keydown", function (e) {
      if (e.key === "Enter" || e.key === " ") { e.preventDefault(); el.fileInput.click(); }
    });
    el.fileInput.addEventListener("change", function () {
      addFiles(el.fileInput.files);
      el.fileInput.value = "";
    });
    ["dragenter", "dragover"].forEach(function (evt) {
      el.dropzone.addEventListener(evt, function (e) {
        e.preventDefault(); e.stopPropagation();
        el.dropzone.classList.add("drag");
      });
    });
    ["dragleave", "dragend"].forEach(function (evt) {
      el.dropzone.addEventListener(evt, function (e) {
        e.preventDefault(); e.stopPropagation();
        el.dropzone.classList.remove("drag");
      });
    });
    el.dropzone.addEventListener("drop", function (e) {
      e.preventDefault(); e.stopPropagation();
      el.dropzone.classList.remove("drag");
      if (e.dataTransfer && e.dataTransfer.files) addFiles(e.dataTransfer.files);
    });
  }

  /* ---- init ---- */
  function init() {
    initTheme();
    buildSidebar();
    wireDropzone();
    el.runBtn.addEventListener("click", run);
    el.readMetaBtn.addEventListener("click", readMetadata);
    selectOp(OPS[0]);
    ping();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
