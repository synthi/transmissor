-- =========================================================
-- UI — Transmissor v1.5.0
-- OLED redraw — guaranteed screen.update()
-- Layout: Name: value [===plasma bar===]
-- Values right-aligned at x=88, bars at x=93 (35px)
-- No distance bar — presets at bottom with spacing
-- =========================================================

function ui_redraw()
  screen.clear()

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
    screen.move(0, 10)
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
      screen.move(128, 10)
      screen.text_right(sa and "2/2" or "1/2")
    end

    -- LINES 2-4: Parameters (spaced at y=24, 36, 48)
    if dm then
      local d = params:get("distance") or 0
      screen.level(10)
      screen.move(0, 28)
      screen.text("Distance: " .. string.format("%.2f", d))
      screen.level(4)
      screen.move(0, 42)
      screen.text("ALL ENCODERS > DISTANCE")
      -- plasma bar for distance
      draw_plasma_bar(24, d, 0, 1)
      draw_plasma_bar(36, d, 0, 1)
      draw_plasma_bar(48, d, 0, 1)
    else
      local idx1 = sa and (page.shift[1] or page.main[1]) or page.main[1]
      local idx2 = sa and (page.shift[2] or page.main[2]) or page.main[2]
      local idx3 = sa and (page.shift[3] or page.main[3]) or page.main[3]

      if idx1 then
        draw_param_line(24, idx1)
      end
      if idx2 then
        draw_param_line(36, idx2)
      end
      if idx3 then
        draw_param_line(48, idx3)
      end
    end

    -- LINE 5: Preset info (bottom, with breathing room)
    local fid_name = (fidelity_names or {})[cf] or "MANUAL"
    local int_name = (interference_names or {})[ci] or "MANUAL"
    screen.level(4)
    screen.move(0, 61)
    screen.text("F" .. cf .. ":" .. fid_name .. "  I" .. ci .. ":" .. int_name)

  end)

  if not ok then
    screen.level(15)
    screen.move(0, 30)
    screen.text("UI:" .. tostring(err):sub(1, 20))
    print("[Transmissor] ui_redraw error: " .. tostring(err))
  end

  -- GUARANTEED screen.update
  screen.update()
end

-- =========================================================
-- DRAW PARAM LINE
-- Layout: Name: value  [===plasma bar===]
-- Name drawn at x=0, value right-aligned at x=88
-- Plasma bar at x=93, width=35 (93+35=128)
-- =========================================================

function draw_param_line(y_pos, param_id)
  if not param_id then return end

  local value = params:get(param_id) or 0
  local minv = param_min(param_id)
  local maxv = param_max(param_id)

  -- Get display name from param definition
  local p = params:lookup_param(param_id)
  local name = ""
  if p and p.name then
    name = p.name
  else
    name = param_id
  end

  -- Format value: 2 decimals for small ranges, 0 decimals for Hz/kHz ranges
  local range = math.abs(maxv - minv)
  local val_str
  if range > 200 then
    val_str = string.format("%.0f", value)
  else
    val_str = string.format("%.2f", value)
  end

  -- Draw name + ":" at x=0
  screen.level(8)
  screen.move(0, y_pos)
  -- Truncate name only if it would collide with value area
  -- Safe limit: ~14 chars (names are all <= 14 chars)
  local name_display = string.sub(name, 1, 14) .. ":"
  screen.text(name_display)

  -- Draw value right-aligned at x=88
  screen.level(15)
  screen.move(88, y_pos)
  screen.text_right(val_str)

  -- Draw plasma bar
  local norm = (value - minv) / (maxv - minv + 0.0001)
  norm = util.clamp(norm, 0, 1)
  draw_plasma_bar(y_pos, norm, 0, 1)
end

-- =========================================================
-- DRAW PLASMA BAR
-- Bar at x=93, width=35. Brightness increases with value.
-- Low values: dim (level 3), high values: bright (level 15)
-- =========================================================

function draw_plasma_bar(y_pos, norm, minv, maxv)
  norm = util.clamp(norm, 0, 1)

  local bar_x = 93
  local bar_w = 35
  local filled = math.ceil(norm * bar_w)

  -- Filled portion: plasma brightness (level 3→15 based on value)
  if filled > 0 then
    local brightness = math.floor(3 + norm * 12)
    screen.level(brightness)
    screen.rect(bar_x, y_pos - 5, filled, 5)
    screen.fill()
  end

  -- Unfilled portion: very dim
  if filled < bar_w then
    screen.level(1)
    screen.rect(bar_x + filled, y_pos - 5, bar_w - filled, 5)
    screen.fill()
  end
end

-- =========================================================
-- EXPORT
-- =========================================================

return {
  ui_redraw = ui_redraw,
  draw_param_line = draw_param_line,
  draw_plasma_bar = draw_plasma_bar
}