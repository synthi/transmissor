-- =========================================================
-- UI — Transmissor v1.0.8
-- OLED redraw — guaranteed screen.update() + debug
-- =========================================================

local redraw_count = 0

function ui_redraw()
  redraw_count = redraw_count + 1

  -- Debug: first 5 frames
  if redraw_count <= 5 then
    print("[TRMS UI] redraw #" .. redraw_count ..
      " pages=" .. tostring(pages ~= nil) ..
      " ui_redraw=OK")
  end

  screen.clear()

  -- Defensive: verify pages exists
  if not pages then
    screen.level(15)
    screen.move(0, 20)
    screen.text("ERR: pages=nil #" .. redraw_count)
    screen.update()
    return
  end

  local ok, err = pcall(function()
    local cp = _G.current_page or 1
    local dm = _G.distance_mode or false
    local sa = _G.shift_active or false
    local cf = _G.current_fidelity or 1
    local ci = _G.current_interference or 1

    local page = pages[cp]
    if not page then page = pages[1] end
    local page_str = string.format("%02d/08", cp)

    -- Header suffix
    local suffix = ""
    if dm then
      suffix = " DIST"
    elseif sa then
      suffix = " SH"
    end

    -- LINE 1: Header
    screen.level(15)
    screen.move(0, 8)
    screen.text("TRMS " .. page_str .. " " .. page.name .. suffix)

    -- LINES 2-4: Parameters
    if dm then
      local d = params:get("distance") or 0
      screen.level(8)
      screen.move(0, 20)
      screen.text("DIST")
      screen.level(15)
      screen.rect(45, 14, math.floor(d * 83), 6)
      screen.fill()
      screen.level(8)
      screen.move(134, 20)
      screen.text(string.format("%.2f", d))
      screen.level(3)
      screen.move(0, 30)
      screen.text("ALL ENCODERS > DISTANCE")
    else
      local idx1 = sa and page.shift[1] or page.main[1]
      local idx2 = sa and page.shift[2] or page.main[2]
      local idx3 = sa and page.shift[3] or page.main[3]

      if idx1 then
        draw_param_bar(20, idx1, params:get(idx1) or 0,
          param_min(idx1), param_max(idx1))
      end
      if idx2 then
        draw_param_bar(30, idx2, params:get(idx2) or 0,
          param_min(idx2), param_max(idx2))
      end
      if idx3 then
        draw_param_bar(40, idx3, params:get(idx3) or 0,
          param_min(idx3), param_max(idx3))
      end
    end

    -- LINE 5: Preset info
    local fid_name = (fidelity_names or {})[cf] or "MAN"
    local int_name = (interference_names or {})[ci] or "MAN"
    screen.level(4)
    screen.move(0, 50)
    screen.text("FID:" .. cf .. " " .. fid_name .. "  INT:" .. ci .. " " .. int_name)

    -- LINE 6: Distance bar (always)
    local dist_val = params:get("distance") or 0
    local bar_pixels = math.floor(dist_val * 128)
    if bar_pixels > 0 then
      screen.level(15)
      screen.rect(0, 58, bar_pixels, 5)
      screen.fill()
    end
    screen.level(1)
    screen.rect(bar_pixels, 58, 128 - bar_pixels, 5)
    screen.fill()
  end)

  if not ok then
    -- Show error on screen so user can see what failed
    screen.level(15)
    screen.move(0, 30)
    screen.text("UI:" .. tostring(err):sub(1, 20))
    -- Debug: only print first 3 errors
    if redraw_count < 4 then
      print("[Transmissor] ui_redraw error #" .. redraw_count .. ": " .. tostring(err))
    end
  end

  -- GUARANTEED screen.update — ALWAYS runs
  screen.update()
end

-- =========================================================
-- DRAW PARAM BAR
-- =========================================================

function draw_param_bar(y_pos, param_name, value, minv, maxv)
  if not param_name then return end
  minv = minv or 0
  maxv = maxv or 1
  value = value or 0

  local norm = (value - minv) / (maxv - minv + 0.0001)
  norm = util.clamp(norm, 0, 1)

  local label_w = 45
  local bar_w = 50
  local bar_x = label_w
  local filled = math.floor(norm * bar_w)

  screen.level(15)
  screen.move(0, y_pos)
  screen.text(string.sub(param_name, 1, 6))

  if filled > 0 then
    screen.level(15)
    screen.rect(bar_x, y_pos - 6, filled, 6)
    screen.fill()
  end
  if filled < bar_w then
    screen.level(2)
    screen.rect(bar_x + filled, y_pos - 6, bar_w - filled, 6)
    screen.fill()
  end

  screen.level(8)
  screen.move(bar_x + bar_w + 3, y_pos)
  local val_str
  if math.abs(maxv - minv) < 0.01 then
    val_str = string.format("%.0f", value)
  elseif math.abs(maxv - minv) < 0.1 then
    val_str = string.format("%.3f", value)
  elseif math.abs(maxv - minv) < 1 then
    val_str = string.format("%.2f", value)
  elseif math.abs(maxv - minv) < 10 then
    val_str = string.format("%.1f", value)
  else
    val_str = string.format("%.0f", value)
  end
  screen.text(val_str)
end

-- =========================================================
-- EXPORT
-- =========================================================

return {
  ui_redraw = ui_redraw,
  draw_param_bar = draw_param_bar
}