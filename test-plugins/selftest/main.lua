local results, ok = {}, true
local function check(name, cond) results[#results+1] = (cond and "PASS " or "FAIL ") .. name; ok = ok and cond end
mc.after(200, function()
  local d = "minecraft:overworld"
  check("set_block", mc.world.set_block(d, 5, 100, 5, "minecraft:diamond_block"))
  check("get_block", mc.world.get_block(d, 5, 100, 5) == "minecraft:diamond_block")
  check("content registered", mc.world.set_block(d, 6, 100, 5, "ruby:ruby_block") and mc.world.get_block(d, 6, 100, 5) == "ruby:ruby_block")
  check("ore block registered", mc.world.set_block(d, 7, 100, 5, "ruby:ruby_ore"))
  check("count/take item api", type(mc.count_item) == "function" and type(mc.take_item) == "function")
  check("time", type(mc.world.time(d)) == "number")
  check("run command", mc.run("hello"))
  check("players", #mc.players() == 0)
  check("sandbox", io == nil and os.execute == nil and load == nil)
  mc.storage.set("last", os.time())
  check("storage", mc.storage.get("last") ~= nil)
  check("bad perms rejected", not pcall(mc.hud.text, 1, 1, "x"))
  check("unknown block errors", not pcall(mc.world.set_block, d, 0, 100, 0, "minecraft:not_a_block"))
  for _, r in ipairs(results) do mc.log(r) end
  mc.log(ok and "SELFTEST RESULT: ALL PASSED" or "SELFTEST RESULT: FAILURES")
  -- end-to-end: remove a plugin, reinstall it from the GitHub registry (SHA-256 verified)
  -- worldgen: scan a 48x48 area underground for generated ruby ore, one x-row per tick
  local found, x = 0, -16
  local scan
  scan = mc.every(1, function()
    for z = -16, 15 do for y = -48, 40 do
      if mc.world.get_block(d, x, y, z) == "ruby:ruby_ore" then found = found + 1 end
    end end
    x = x + 1
    if x >= 16 then
      scan:cancel()
      mc.log((found > 0 and "PASS" or "FAIL") .. " worldgen ruby ore found: " .. found)
      mc.log(found > 0 and "WORLDGEN OK" or "WORLDGEN MISSING")
    end
  end)
  mc.run("mca blockhiding on")
  mc.run("mca blockhiding off")
  mc.run("mca remove xtoxray")
  mc.run("mca install xtoxray")
  mc.after(260, function()
    mc.run("hello")
    mc.after(20, function() mc.run("stop") end)
  end)
end)
