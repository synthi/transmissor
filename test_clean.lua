-- test_clean.lua — Minimal test to isolate display freeze
-- Load this script to test if display works at all

engine.name = 'Transmissor'

function init()
  print("[TEST] init done")
end

function redraw()
  screen.clear()
  screen.level(15)
  screen.move(0, 30)
  screen.text("CLEAN " .. os.clock())
  screen.update()
end

function cleanup()
  print("[TEST] cleanup")
end