extends CharacterBody2D  # ⭐ 改为CharacterBody2D

# NPC信息
@export var npc_name: String = "仪玄"
@export var npc_title: String = "云岿山门主"

# NPC外观配置
@export var sprite_frames: SpriteFrames = null  # 自定义精灵帧资源

# NPC移动配置 ⭐ 
@export var move_speed: float = 20.0  # 移动速度
@export var wander_enabled: bool = true  # 是否启用巡逻
@export var wander_range: float = 200.0  # 巡逻范围
@export var wander_interval_min: float = 3.0  # 最小巡逻间隔(秒)
@export var wander_interval_max: float = 8.0  # 最大巡逻间隔(秒)

# 当前对话内容(从后端获取)
var current_dialogue: String = ""

# 节点引用
@onready var animated_sprite: AnimatedSprite2D = $AnimatedSprite2D
@onready var interaction_area: Area2D = $InteractionArea
@onready var name_label: Label = $NameLabel
@onready var dialogue_label: Label = $DialogueLabel

# ✨ 【新增】情绪气泡节点引用 (用 get_node_or_null 防止你还没建节点就崩溃)
@onready var mood_bubble: Sprite2D = get_node_or_null("MoodBubble")

# ✨ 【新增】预加载情绪图片 (路径如果不对会报错，你可以随便换两张图或者后续自己画)
# 如果你暂时没有图片，把这三行注释掉，我们在底下的方法里用变色来代替！
# var img_happy = preload("res://assets/ui/happy.png")
# var img_angry = preload("res://assets/ui/angry.png")
# var img_sleep = preload("res://assets/ui/sleep.png")

# 交互提示 (可选节点)
var interaction_hint: Label = null

# 玩家引用
var player: Node = null

# 巡逻相关变量 ⭐ 
var wander_target: Vector2 = Vector2.ZERO  # 巡逻目标位置
var wander_timer: float = 0.0  # 巡逻计时器
var is_wandering: bool = false  # 是否正在巡逻
var is_interacting: bool = false  # 是否正在与玩家交互
var spawn_position: Vector2 = Vector2.ZERO  # 出生位置

func _ready():
	# 添加到npcs组 ⭐ 
	add_to_group("npcs")

	# 设置NPC名字
	name_label.text = npc_name

	# 连接交互区域信号
	interaction_area.body_entered.connect(_on_body_entered)
	interaction_area.body_exited.connect(_on_body_exited)

	# 初始化对话标签
	dialogue_label.text = ""
	dialogue_label.visible = false
	
	# ✨ 【新增】隐藏气泡，并连接全局情绪信号
	if mood_bubble:
		mood_bubble.hide()
	# ⚠️ 请确保你的全局网络脚本叫 ApiClient。如果叫别的，这里需要改！
	if has_node("/root/APIClient"):
		get_node("/root/APIClient").mood_changed.connect(_on_mood_changed)

	# 尝试获取交互提示节点
	interaction_hint = get_node_or_null("InteractionHint")
	if interaction_hint:
		interaction_hint.text = "按E交互"
		interaction_hint.visible = false

	# 设置自定义精灵帧
	if sprite_frames != null:
		animated_sprite.sprite_frames = sprite_frames

	# 播放默认动画
	if animated_sprite.sprite_frames != null and animated_sprite.sprite_frames.has_animation("idle"):
		animated_sprite.play("idle")

	# 记录出生位置 ⭐ 
	spawn_position = global_position

	# 初始化巡逻计时器 ⭐ 
	if wander_enabled:
		wander_timer = randf_range(wander_interval_min, wander_interval_max)
		choose_new_wander_target()

# ✨ 【新增】情绪信号接收与画面表现核心逻辑
func _on_mood_changed(target_npc_id: String, new_mood: String):
	# 如果情绪变动的不是自己，直接跳过
	if target_npc_id != npc_name:
		return
		
	# 如果你还没创建 MoodBubble 节点，为了防止游戏崩溃，我们直接给整个身体上滤镜
	var target_visual = mood_bubble if mood_bubble else animated_sprite
		
	match new_mood:
		"HAPPY":
			if mood_bubble: mood_bubble.show()
			# 如果有图解开下面这行： mood_bubble.texture = img_happy
			target_visual.modulate = Color(1.0, 0.8, 0.8) # 开心的时候身体/气泡变粉红
		"ANGRY":
			if mood_bubble: mood_bubble.show()
			# 如果有图解开下面这行： mood_bubble.texture = img_angry
			target_visual.modulate = Color(1.0, 0.3, 0.3) # 生气变红
		"SLEEPING":
			if mood_bubble: mood_bubble.show()
			# 如果有图解开下面这行： mood_bubble.texture = img_sleep
			target_visual.modulate = Color(0.5, 0.5, 0.8) # 睡觉变暗蓝
			
			# 特殊表现：强制她回出生点睡觉，停止巡逻
			wander_target = spawn_position
			is_wandering = true
		"CALM", _:
			if mood_bubble: mood_bubble.hide()
			target_visual.modulate = Color(1, 1, 1) # 恢复正常原色

func _on_body_entered(body: Node2D):
	if body.is_in_group("player"):
		player = body
		if player.has_method("set_nearby_npc"):
			player.set_nearby_npc(self)
		show_interaction_hint()

func _on_body_exited(body: Node2D):
	if body.is_in_group("player"):
		if player != null and player.has_method("set_nearby_npc"):
			player.set_nearby_npc(null)
		player = null
		hide_interaction_hint()

func show_interaction_hint():
	if interaction_hint:
		interaction_hint.visible = true

func hide_interaction_hint():
	if interaction_hint:
		interaction_hint.visible = false

func update_dialogue(dialogue: String):
	current_dialogue = dialogue
	dialogue_label.text = dialogue
	dialogue_label.visible = true
	await get_tree().create_timer(10.0).timeout
	dialogue_label.visible = false

func get_npc_name() -> String:
	return npc_name

func get_npc_title() -> String:
	return npc_title

# ⭐ 物理更新 - 处理移动
func _physics_process(delta: float):
	if is_interacting:
		velocity = Vector2.ZERO
		move_and_slide()
		if animated_sprite.sprite_frames != null and animated_sprite.sprite_frames.has_animation("idle"):
			animated_sprite.play("idle")
		return

	if not wander_enabled:
		return

	wander_timer -= delta

	if wander_timer <= 0:
		choose_new_wander_target()
		wander_timer = randf_range(wander_interval_min, wander_interval_max)

	if is_wandering:
		if global_position.distance_to(wander_target) < 10:
			is_wandering = false
			velocity = Vector2.ZERO
			move_and_slide()
			if animated_sprite.sprite_frames != null and animated_sprite.sprite_frames.has_animation("idle"):
				animated_sprite.play("idle")
		else:
			var direction = (wander_target - global_position).normalized()
			velocity = direction * move_speed
			move_and_slide()
			update_animation(direction)
	else:
		velocity = Vector2.ZERO
		move_and_slide()
		if animated_sprite.sprite_frames != null and animated_sprite.sprite_frames.has_animation("idle"):
			animated_sprite.play("idle")

# ⭐ 选择新的巡逻目标
func choose_new_wander_target():
	var offset = Vector2(
		randf_range(-wander_range, wander_range),
		randf_range(-wander_range, wander_range)
	)
	wander_target = spawn_position + offset
	is_wandering = true

# ⭐ 更新动画
func update_animation(direction: Vector2):
	if animated_sprite.sprite_frames == null:
		return

	if direction.length() > 0:
		if abs(direction.x) > abs(direction.y):
			if direction.x > 0:
				if animated_sprite.sprite_frames.has_animation("walk_right"):
					animated_sprite.play("walk_right")
				elif animated_sprite.sprite_frames.has_animation("walk"):
					animated_sprite.play("walk")
					animated_sprite.flip_h = false
			else:
				if animated_sprite.sprite_frames.has_animation("walk_left"):
					animated_sprite.play("walk_left")
				elif animated_sprite.sprite_frames.has_animation("walk"):
					animated_sprite.play("walk")
					animated_sprite.flip_h = true
		else:
			if direction.y > 0:
				if animated_sprite.sprite_frames.has_animation("walk_down"):
					animated_sprite.play("walk_down")
				elif animated_sprite.sprite_frames.has_animation("walk"):
					animated_sprite.play("walk")
			else:
				if animated_sprite.sprite_frames.has_animation("walk_up"):
					animated_sprite.play("walk_up")
				elif animated_sprite.sprite_frames.has_animation("walk"):
					animated_sprite.play("walk")
	else:
		if animated_sprite.sprite_frames.has_animation("idle"):
			animated_sprite.play("idle")

# ⭐ 设置交互状态
func set_interacting(interacting: bool):
	is_interacting = interacting
