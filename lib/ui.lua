-- =========================================================
-- UI — Transmissor v1.4.1
-- OLED redraw — guaranteed screen.update()
-- Layout: NAME: VALUE [===bar===]
-- Bar is 32px (35% shorter than before), right-aligned
-- Header: page name left, shift page indicator right
-- =========================================================

function ui_redraw()
  screen.clear()

  -- Defensive: verify pages exists
  if not pages then
    screen.level(15)
    screen.move(0, 20)
    screen.text("ERR: pages=nil")
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

    -- LINE 1: Header — page name left, shift indicator right
    screen.level(15)
    screen.move(0, 8)
    screen.text(page.name)

    -- Shift indicator: "1/2" or "2/2" (only if page has shift params)
    local has_shift = false
    if page.shift then
      for i = 1, 3 do
        if page.shift[i] then has_shift = true; break end
      end
    end
    if has_shift then
      screen.level(6)
      screen.move(128, 8)
      screen.text_right(sa and "2/2" or "1/2")
    end

    -- LINES 2-4: Parameters
    if dm then
      local d = params:get("distance") or 0
      screen.level(8)
      screen.move(0, 20)
      screen.text("DISTANCE:")
      screen.level(15)
      screen.move(55, 20)
      screen.text(string.format("%.2f", d))
      -- bar
      local bar_x, bar_w = 92, 32
      local filled = math.floor(d * bar_w)
      if filled > 0 then
        screen.level(15)
        screen.rect(bar_x, 14, filled, 6)
        screen.fill()
      end
      if filled < bar_w then
        screen.level(2)
        screen.rect(bar_x + filled, 14, bar_w - filled, 6)
        screen.fill()
      end
      screen.level(3)
      screen.move(0, 30)
      screen.text("ALL ENCODERS > DISTANCE")
    else
      local idx1 = sa and (page.shift[1] or page.main[1]) or page.main[1]
      local idx2 = sa and (page.shift[2] or page.main[2]) or page.main[2]
      local idx3 = sa and (page.shift[3] or page.main[3]) or page.main[3]

      if idx1 then
        draw_param_bar(18, idx1, params:get(idx1) or 0,
          param_min(idx1), param_max(idx1))
      end
      if idx2 then
        draw_param_bar(28, idx2, params:get(idx2) or 0,
          param_min(idx2), param_max(idx2))
      end
      if idx3 then
        draw_param_bar(38, idx3, params:get(idx3) or 0,
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
    screen.level(15)
    screen.move(0, 30)
    screen.text("UI:" .. tostring(err):sub(1, 20))
    print("[Transmissor] ui_redraw error: " .. tostring(err))
  end

  -- GUARANTEED screen.update — ALWAYS runs
  screen.update()
end

-- =========================================================
-- DRAW PARAM BAR
-- Layout: NAME: VALUE [===bar===]
-- Text left (x=0..88), bar right (x=92, 32px wide)
-- =========================================================

function draw_param_bar(y_pos, param_name, value, minv, maxv)
  if not param_name then return end
  minv = minv or 0
  maxv = maxv or 1
  value = value or 0

  local norm = (value - minv) / (maxv - minv + 0.0001)
  norm = util.clamp(norm, 0, 1)

  -- Get display name from param (already UPPERCASE in parameters.lua)
  local p = params:lookup_param(param_name)
  local display_name = ""
  if p and p.name then
    display_name = p.name
  else
    display_name = string.upper(param_name)
  end

  -- Format value string
  local range = math.abs(maxv - minv)
  local val_str
  if range < 0.01 then
    val_str = string.format("%.0f", value)
  elseif range < 0.1 then
    val_str = string.format("%.3f", value)
  elseif range < 1 then
    val_str = string.format("%.2f", value)
  elseif range < 10 then
    val_str = string.format("%.1f", value)
  else
    val_str = string.format("%.0f", value)
  end

  -- Build text: "NAME: VALUE" (truncate to fit before bar at x=88)
  local text = display_name .. ": " .. val_str

  screen.level(15)
  screen.move(0, y_pos)
  screen.text(string.sub(text, 1, 15))

  -- Bar (right side: x=92, w=32)
  local bar_x = 92
  local bar_w = 32
  local filled = math.floor(norm * bar_w)

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
end

-- =========================================================
-- EXPORT
-- =========================================================

return {
  ui_redraw = ui_redraw,
  draw_param_bar = draw_param_bar
}